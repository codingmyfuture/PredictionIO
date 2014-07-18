package io.prediction.workflow

import io.prediction.controller.Params
import io.prediction.controller.EmptyParams
import io.prediction.core.Doer
import scala.language.existentials

//import io.prediction.core.BaseEvaluator
//import io.prediction.core.BaseEngine
import io.prediction.core.BaseAlgorithm
import io.prediction.core.LModelAlgorithm
import io.prediction.controller.java.LJavaDataSource
import io.prediction.controller.java.LJavaPreparator
import io.prediction.controller.java.LJavaAlgorithm
import io.prediction.controller.java.LJavaServing
import io.prediction.controller.java.JavaMetrics
import io.prediction.controller.Engine
import io.prediction.controller.EngineParams
import io.prediction.controller.java.JavaUtils
import io.prediction.controller.java.JavaEngine

import io.prediction.controller.LAlgorithm

import com.github.nscala_time.time.Imports.DateTime

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.SparkConf

import java.io.FileOutputStream
import java.io.ObjectOutputStream
import java.io.FileInputStream
import java.io.ObjectInputStream
import scala.collection.JavaConversions._
import java.lang.{ Iterable => JIterable }
import java.util.{ Map => JMap }

import io.prediction.core._
import io.prediction._

import org.apache.spark.rdd.RDD

import scala.reflect.Manifest

import grizzled.slf4j.Logging
//import io.prediction.java._

import scala.reflect._

// FIXME: move to better location.
object WorkflowContext extends Logging {
  def apply(batch: String = "", env: Map[String, String] = Map()): SparkContext = {
    val conf = new SparkConf().setAppName(s"PredictionIO: $batch")
    info(s"Environment received: ${env}")
    env.map(kv => conf.setExecutorEnv(kv._1, kv._2))
    info(s"SparkConf executor environment: ${conf.getExecutorEnv}")
    conf.set("spark.local.dir", "~/tmp/spark")
    conf.set("spark.executor.memory", "7g")

    val sc = new SparkContext(conf)
    return sc
  }
}

object DebugWorkflow {
  def debugString[D](data: D): String = {
    val s: String = data match {
      case rdd: RDD[_] => {
        debugString(rdd.collect)
      }
      case array: Array[_] => {
        "[" + array.map(debugString).mkString(",") + "]"
      }
      case d: AnyRef => {
        d.toString
      }
      case null => "null"
    }
    s
  }
}


// skipOpt = true: use slow parallel model for prediction, requires one extra
// join stage.
class AlgoServerWrapper[Q, P, A](
    val algos: Array[_ <: BaseAlgorithm[_,_,_,Q,P]],
    val serving: BaseServing[_, Q, P],
    val skipOpt: Boolean = false,
    val verbose: Int = 0)
extends Serializable {
  
  // Use algo.predictBase
  def onePassPredict(
    modelIter: Iterator[(AI, Any)], 
    queryActualIter: Iterator[(Q, A)])
  : Iterator[(Q, P, A)] = {
    val models = modelIter.toSeq.sortBy(_._1).map(_._2)

    queryActualIter.map{ case(query, actual) => {
      val predictions = algos.zipWithIndex.map {
        case (algo, i) => algo.predictBase(models(i), query)
      }
      val prediction = serving.serveBase(query, predictions)

      (query, prediction, actual)
    }}
  }

  // Use algo.predictBase
  def predictLocalModel(models: Seq[RDD[Any]], input: RDD[(Q, A)])
  : RDD[(Q, P, A)] = {
    if (verbose > 0) {
      println("predictionLocalModel")
    }
    val sc = models.head.context
    // must have only one partition since we need all models per feature.
    // todo: duplicate indexedModel into multiple partition.
    val reInput = input.coalesce(numPartitions = 1)

    val indexedModels: Seq[RDD[(AI, Any)]] = models.zipWithIndex
      .map { case (rdd, ai) => rdd.map(m => (ai, m)) }

    val rddModel: RDD[(AI, Any)] = sc.union(indexedModels)
      .coalesce(numPartitions = 1)

    rddModel.zipPartitions(reInput)(onePassPredict)
  }

  // Use algo.batchPredictBase
  def predictParallelModel(models: Seq[Any], input: RDD[(Q, A)])
  : RDD[(Q, P, A)] = {
    if (verbose > 0) { 
      println("predictionParallelModel")
    }

    // Prefixed with "i" stands for "i"ndexed
    val iInput: RDD[(QI, (Q, A))] = input.zipWithUniqueId.map(_.swap)

    val iQuery: RDD[(QI, Q)] = iInput.map(e => (e._1, e._2._1))
    val sc = input.context

    // Each algo/model is run independely.
    val iAlgoPredictionSeq: Seq[RDD[(QI, (AI, P))]] = models
      .zipWithIndex
      .map { case (model, ai) => {
        algos(ai)
          .batchPredictBase(model, iQuery)
          .map{ case (fi, p) => (fi, (ai, p)) }
      }}

    val iAlgoPredictions: RDD[(QI, Seq[P])] = sc
      .union(iAlgoPredictionSeq)
      .groupByKey
      .mapValues { _.toSeq.sortBy(_._1).map(_._2) }

    val joined: RDD[(QI, (Seq[P], (Q, A)))] = iAlgoPredictions.join(iInput)
    
    if (verbose > 2) {
      println("predictionParallelModel.before combine")
      joined.collect.foreach {  case(fi, (ps, (q, a))) => {
        val pstr = DebugWorkflow.debugString(ps)
        val qstr = DebugWorkflow.debugString(q)
        val astr = DebugWorkflow.debugString(a)
        //e => println(DebugWorkflow.debugString(e))
        println(s"I: $fi Q: $qstr A: $astr Ps: $pstr")
      }}
    }

    val combined: RDD[(QI, (Q, P, A))] = joined
    .mapValues{ case (predictions, (query, actual)) => {
      val prediction = serving.serveBase(query, predictions)
      (query, prediction, actual)
    }}

    if (verbose > 2) {
      println("predictionParallelModel.after combine")
      combined.collect.foreach { case(qi, (q, p, a)) => {
        val qstr = DebugWorkflow.debugString(q)
        val pstr = DebugWorkflow.debugString(p)
        val astr = DebugWorkflow.debugString(a)
        println(s"I: $qi Q: $qstr A: $astr P: $pstr")
      }}
    }

    combined.values
  }

  def predict(models: Seq[Any], input: RDD[(Q, A)])
  : RDD[(Q, P, A)] = {
    // We split the prediction into multiple mode.
    // If all algo support using local model, we will run against all of them
    // in one pass.
    val someNonLocal = algos
      //.exists(!_.isInstanceOf[LAlgorithm[_, _, _, Q, P]])
      .exists(!_.isInstanceOf[LModelAlgorithm[_, Q, P]])

    if (!someNonLocal && !skipOpt) {
      // When algo is local, the model is the only element in RDD[M].
      val localModelAlgo = algos
        .map(_.asInstanceOf[LModelAlgorithm[_, Q, P]])
        //.map(_.asInstanceOf[LAlgorithm[_, _, _, Q, P]])
      val rddModels = localModelAlgo.zip(models)
        .map{ case (algo, model) => algo.getModel(model) }
      predictLocalModel(rddModels, input)
    } else {
      predictParallelModel(models, input)
    }
  }
}

class MetricsWrapper[
    MDP, MQ, MP, MA, MU: ClassTag, MR, MMR <: AnyRef](
    val metrics: BaseMetrics[_,MDP,MQ,MP,MA,MU,MR,MMR]) 
extends Serializable {
  def computeUnit[Q, P, A](input: RDD[(Q, P, A)]): RDD[MU] = {
    input
    .map{ e => (
      e._1.asInstanceOf[MQ],
      e._2.asInstanceOf[MP],
      e._3.asInstanceOf[MA]) }
    .map(metrics.computeUnitBase)
  }

  def computeSet[DP](input: (DP, Iterable[MU])): (MDP, MR) = {
    val mdp = input._1.asInstanceOf[MDP]
    val results = metrics.computeSetBase(mdp, input._2.toSeq)
    (mdp, results)
  }

  def computeMultipleSets(input: Array[(MDP, MR)]): MMR = {
    // maybe sort them.
    metrics.computeMultipleSetsBase(input)
  }
}

object APIDebugWorkflow {
  def runEngine[
      DP, TD, PD, Q, P, A,
      MU : ClassTag, MR : ClassTag, MMR <: AnyRef :ClassTag 
      ](
      batch: String = "",
      verbose: Int = 2,
      engine: Engine[TD, DP, PD, Q, P, A],
      engineParams: EngineParams,
      metricsClassOpt
        : Option[Class[_ <: BaseMetrics[_ <: Params, DP, Q, P, A, MU, MR, MMR]]]
        = None,
      metricsParams: Params = EmptyParams()) {

    run(
      batch = batch,
      verbose = verbose,
      dataSourceClassOpt = Some(engine.dataSourceClass),
      dataSourceParams = engineParams.dataSourceParams,
      preparatorClassOpt = Some(engine.preparatorClass),
      preparatorParams = engineParams.preparatorParams,
      algorithmClassMapOpt = Some(engine.algorithmClassMap),
      algorithmParamsList = engineParams.algorithmParamsList,
      servingClassOpt = Some(engine.servingClass),
      servingParams = engineParams.servingParams,
      metricsClassOpt = metricsClassOpt,
      metricsParams = metricsParams
    )
  }
     
  def run[
      DP, TD, PD, Q, P, A,
      MU : ClassTag, MR : ClassTag, MMR <: AnyRef :ClassTag 
      ](
      batch: String = "",
      verbose: Int = 2,
      dataSourceClassOpt
        : Option[Class[_ <: BaseDataSource[_ <: Params, DP, TD, Q, A]]] = None,
      dataSourceParams: Params = EmptyParams(),
      preparatorClassOpt
        : Option[Class[_ <: BasePreparator[_ <: Params, TD, PD]]] = None,
      preparatorParams: Params = EmptyParams(),
      algorithmClassMapOpt
        : Option[Map[String, Class[_ <: BaseAlgorithm[_ <: Params, PD, _, Q, P]]]] 
        = None,
      algorithmParamsList: Seq[(String, Params)] = null,
      servingClassOpt: Option[Class[_ <: BaseServing[_ <: Params, Q, P]]]
        = None,
      servingParams: Params = EmptyParams(),
      metricsClassOpt
        : Option[Class[_ <: BaseMetrics[_ <: Params, DP, Q, P, A, MU, MR, MMR]]]
        = None,
      metricsParams: Params = EmptyParams()) {
    runTypeless(
        batch, verbose, 
        dataSourceClassOpt, dataSourceParams,
        preparatorClassOpt, preparatorParams, 
        algorithmClassMapOpt, algorithmParamsList, 
        servingClassOpt, servingParams, 
        metricsClassOpt, metricsParams)
  }

  // ***Do not directly call*** this method unless you know what you are doing.
  // When engine and metrics are instantiated direcly from CLI, the compiler has
  // no way to know their actual type parameter during compile time. To rememdy
  // this restriction, we have to let engine and metrics to be casted to their
  // own type parameters, and force cast their type during runtime.
      //MU : ClassTag, MR : ClassTag, MMR <: AnyRef :ClassTag 
  def runEngineTypeless[
      DP, TD, PD, Q, P, A,
      MDP, MQ, MP, MA,
      MU, MR, MMR <: AnyRef
      ](
      batch: String = "",
      verbose: Int = 2,
      engine: Engine[TD, DP, PD, Q, P, A],
      engineParams: EngineParams,
      /*
      metricsClassOpt
        : Option[Class[_ <: BaseMetrics[_ <: Params, MDP, MQ, MP, MA, MU, MR, MMR]]]
        = None,
      */
      metricsClass
        : Class[_ <: BaseMetrics[_ <: Params, MDP, MQ, MP, MA, MU, MR, MMR]],
      metricsParams: Params = EmptyParams()) {

    runTypeless(
      batch = batch,
      verbose = verbose,
      dataSourceClassOpt = Some(engine.dataSourceClass),
      dataSourceParams = engineParams.dataSourceParams,
      preparatorClassOpt = Some(engine.preparatorClass),
      preparatorParams = engineParams.preparatorParams,
      algorithmClassMapOpt = Some(engine.algorithmClassMap),
      algorithmParamsList = engineParams.algorithmParamsList,
      servingClassOpt = Some(engine.servingClass),
      servingParams = engineParams.servingParams,
      //metricsClassOpt = Some(metricsClass),
      //metricsParams = metricsParams
      metricsClassOpt = None
    )(
      JavaUtils.fakeClassTag[MU], 
      JavaUtils.fakeClassTag[MR],
      JavaUtils.fakeClassTag[MMR])
  }

  // yipjustin: The parameter list has more than 80 columns. But I cannot find a
  // way to spread it to multiple lines while presving the reability.
  def runTypeless[
      DP, TD, PD, Q, P, A,
      MDP, MQ, MP, MA,
      MU : ClassTag, MR : ClassTag, MMR <: AnyRef :ClassTag 
      ](
      batch: String = "",
      verbose: Int = 2,
      dataSourceClassOpt
        : Option[Class[_ <: BaseDataSource[_ <: Params, DP, TD, Q, A]]] = None,
      dataSourceParams: Params = EmptyParams(),
      preparatorClassOpt
        : Option[Class[_ <: BasePreparator[_ <: Params, TD, PD]]] = None,
      preparatorParams: Params = EmptyParams(),
      algorithmClassMapOpt
        : Option[Map[String, Class[_ <: BaseAlgorithm[_ <: Params, PD, _, Q, P]]]] 
        = None,
      algorithmParamsList: Seq[(String, Params)] = null,
      servingClassOpt: Option[Class[_ <: BaseServing[_ <: Params, Q, P]]]
        = None,
      servingParams: Params = EmptyParams(),
      metricsClassOpt
        : Option[Class[_ <: BaseMetrics[_ <: Params, MDP, MQ, MP, MA, MU, MR, MMR]]]
        = None,
      metricsParams: Params = EmptyParams()) {
    println("APIDebugWorkflow.run")
    println("Start spark context")

    val sc = WorkflowContext(batch)

    //if (dataSourceClass == null || dataSourceParams == null) {
    if (dataSourceClassOpt.isEmpty) {
      println("Dataprep Class or Params is null. Stop here");
      return
    }
    
    println("Data Source")
    val dataSource = Doer(dataSourceClassOpt.get, dataSourceParams)

    val evalParamsDataMap
    : Map[EI, (DP, TD, RDD[(Q, A)])] = dataSource
      .readBase(sc)
      .zipWithIndex
      .map(_.swap)
      .toMap

    val localParamsSet: Map[EI, DP] = evalParamsDataMap.map { 
      case(ei, e) => (ei -> e._1)
    }

    val evalDataMap: Map[EI, (TD, RDD[(Q, A)])] = evalParamsDataMap.map {
      case(ei, e) => (ei -> (e._2, e._3))
    }

    println(s"Number of training set: ${localParamsSet.size}")

    if (verbose > 2) {
      evalDataMap.foreach{ case (ei, data) => {
        val (trainingData, testingData) = data
        //val collectedValidationData = testingData.collect
        val trainingDataStr = DebugWorkflow.debugString(trainingData)
        val testingDataStrs = testingData.collect
          .map(DebugWorkflow.debugString)

        println(s"Data Set $ei")
        println(s"Params: ${localParamsSet(ei)}")
        println(s"TrainingData:")
        println(trainingDataStr)
        println(s"TestingData: (count=${testingDataStrs.length})")
        testingDataStrs.foreach { println }
      }}
    }

    println("Data source complete")
    
    if (preparatorClassOpt.isEmpty) {
      println("Preparator is null. Stop here")
      return
    }

    println("Preparator")
    val preparator = Doer(preparatorClassOpt.get, preparatorParams)
    
    val evalPreparedMap: Map[EI, PD] = evalDataMap
    .map{ case (ei, data) => (ei, preparator.prepareBase(sc, data._1)) }

    if (verbose > 2) {
      evalPreparedMap.foreach{ case (ei, pd) => {
        val s = DebugWorkflow.debugString(pd)
        println(s"Prepared Data Set $ei")
        println(s"Params: ${localParamsSet(ei)}")
        println(s"PreparedData: $s")
      }}
    }
  
    println("Preparator complete")
    
    if (algorithmClassMapOpt.isEmpty) {
      println("Algo is null. Stop here")
      return
    }

    println("Algo model construction")

    // Instantiate algos
    val algoInstanceList: Array[BaseAlgorithm[_, PD, _, Q, P]] =
    algorithmParamsList
      .map { 
        case (algoName, algoParams) => 
          Doer(algorithmClassMapOpt.get(algoName), algoParams)
      }
      .toArray

    if (algoInstanceList.length == 0) {
      println("AlgoList has zero length. Stop here")
      return
    }

    // Model Training
    // Since different algo can have different model data, have to use Any.
    // We need to use par here. Since this process allows algorithms to perform
    // "Actions" 
    // (see https://spark.apache.org/docs/latest/programming-guide.html#actions)
    // on RDDs. Meaning that algo may kick off a spark pipeline to for training.
    // Hence, we parallelize this process.
    val evalAlgoModelMap: Map[EI, Seq[(AI, Any)]] = evalPreparedMap
    .par
    .map { case (ei, preparedData) => {

      val algoModelSeq: Seq[(AI, Any)] = algoInstanceList
      .zipWithIndex
      .map { case (algo, index) => {
        val model: Any = algo.trainBase(sc, preparedData)
        (index, model)
      }}

      (ei, algoModelSeq)
    }}
    .seq
    .toMap

    if (verbose > 2) {
      evalAlgoModelMap.map{ case(ei, aiModelSeq) => {
        aiModelSeq.map { case(ai, model) => {
          println(s"Model ei: $ei ai: $ai")
          println(DebugWorkflow.debugString(model))
        }}
      }}
    }

    if (servingClassOpt.isEmpty) {
      println("Serving is null. Stop here")
      return
    }
    val serving = Doer(servingClassOpt.get, servingParams)

    println("Algo prediction")

    val evalPredictionMap
    : Map[EI, RDD[(Q, P, A)]] = evalDataMap.map { case (ei, data) => {
      val validationData: RDD[(Q, A)] = data._2
      val algoModel: Seq[Any] = evalAlgoModelMap(ei)
        .sortBy(_._1)
        .map(_._2)

      val algoServerWrapper = new AlgoServerWrapper[Q, P, A](
        algoInstanceList, serving, skipOpt = false, verbose = verbose)
      (ei, algoServerWrapper.predict(algoModel, validationData))
    }}
    .toMap

    if (verbose > 2) {
      evalPredictionMap.foreach{ case(ei, fpaRdd) => {
        println(s"Prediction $ei $fpaRdd")
        fpaRdd.collect.foreach{ case(f, p, a) => {
          val fs = DebugWorkflow.debugString(f)
          val ps = DebugWorkflow.debugString(p)
          val as = DebugWorkflow.debugString(a)
          println(s"F: $fs P: $ps A: $as")
        }}
      }}
    }

    if (verbose > 0) {
      evalPredictionMap.foreach { case(ei, fpaRdd) => {
        val n = fpaRdd.count()
        println(s"DP $ei has $n rows")
      }}
    }
    
    if (metricsClassOpt.isEmpty) {
      println("Metrics is null. Stop here")
      return
    }

    val metrics = Doer(metricsClassOpt.get, metricsParams)
    val metricsWrapper = new MetricsWrapper(metrics)
    
    // Metrics Unit
    val evalMetricsUnitMap: Map[Int, RDD[MU]] =
      evalPredictionMap.mapValues(metricsWrapper.computeUnit)

    if (verbose > 2) {
      evalMetricsUnitMap.foreach{ case(i, e) => {
        println(s"MetricsUnit: i=$i e=$e")
      }}
    }
    
    // Metrics Set
    val evalMetricsResultsMap
    : Map[EI, RDD[(MDP, MR)]] = evalMetricsUnitMap
    .map{ case (ei, metricsUnits) => {
      val metricsResults
      : RDD[(MDP, MR)] = metricsUnits
        .coalesce(numPartitions=1)
        .glom()
        .map(e => (localParamsSet(ei), e.toIterable))
        .map(metricsWrapper.computeSet)

      (ei, metricsResults)
    }}

    if (verbose > 2) {
      evalMetricsResultsMap.foreach{ case(ei, e) => {
        println(s"MetricsResults $ei $e")
      }}
    }

    val multipleMetricsResults: RDD[MMR] = sc
      .union(evalMetricsResultsMap.values.toSeq)
      .coalesce(numPartitions=1)
      .glom()
      .map(metricsWrapper.computeMultipleSets)

    val metricsOutput: Array[MMR] = multipleMetricsResults.collect

    println(s"DataSourceParams: $dataSourceParams")
    println(s"PreparatorParams: $preparatorParams")
    algorithmParamsList.zipWithIndex.foreach { case (ap, ai) => {
      println(s"Algo: $ai Name: ${ap._1} Params: ${ap._2}")
    }}
    println(s"ServingParams: $servingParams")
    println(s"MetricsParams: $metricsParams")

    metricsOutput foreach { println }
    
    println("APIDebugWorkflow.run completed.") 
  }
}

/*
    dataSourceClass: Class[_ <: LJavaDataSource[DSP, DP, TD, Q, A]],
    dataSourceParams: Params,
    preparatorClass: Class[_ <: LJavaPreparator[PP, TD, PD]],
    preparatorParams: Params,
    algorithmClassMap: 
      JMap[String, Class[_ <: LJavaAlgorithm[_ <: Params, PD, _, Q, P]]],
    algorithmParamsList: JIterable[(String, Params)],
    servingClass: Class[_ <: LJavaServing[SP, Q, P]],
    servingParams: Params,
    metricsClass: Class[_ <: JavaMetrics[MP, DP, Q, P, A, MU, MR, MMR]],
    metricsParams: Params
*/

/*
Ideally, Java could also act as other scala base class. But the tricky part
is in the algorithmClassMap, where javac is not smart enough to match
JMap[String, Class[_ <: LJavaAlgorith[...]]] (which is provided by the
caller) with JMap[String, Class[_ <: BaseAlgo[...]]] (signature of this
function). If we change the caller to use Class[_ <: BaseAlgo[...]], it is
difficult for the engine builder, as we wrap data structures with RDD in the
base class. Hence, we have to sacrifices here, that all Doers calling
JavaAPIDebugWorkflow needs to be Java sub-doers.
*/
object JavaAPIDebugWorkflow {
  def noneIfNull[T](t: T): Option[T] = (if (t == null) None else Some(t))

  // Java doesn't support default parameters. If you only want to test, say,
  // DataSource and PreparatorClass only, please pass null to the other
  // components.
  // Another method is to use JavaEngineBuilder, add only the components you
  // already have. It will handle the missing ones.
      //DSP <: Params, PP <: Params, SP <: Params, MP <: Params,
  def run[
      DP, TD, PD, Q, P, A, MU, MR, MMR <: AnyRef](
    batch: String = "",
    verbose: Int = 2,
    dataSourceClass: Class[_ <: BaseDataSource[_ <: Params, DP, TD, Q, A]],
    dataSourceParams: Params,
    preparatorClass: Class[_ <: BasePreparator[_ <: Params, TD, PD]],
    preparatorParams: Params,
    algorithmClassMap: 
      JMap[String, Class[_ <: BaseAlgorithm[_ <: Params, PD, _, Q, P]]],
    algorithmParamsList: JIterable[(String, Params)],
    servingClass: Class[_ <: BaseServing[_ <: Params, Q, P]],
    servingParams: Params,
    metricsClass: Class[_ <: BaseMetrics[_ <: Params, DP, Q, P, A, MU, MR, MMR]],
    metricsParams: Params
  ) = {

    val scalaAlgorithmClassMap = (
      if (algorithmClassMap == null) null
      else Map(algorithmClassMap.toSeq:_ *))
      
    val scalaAlgorithmParamsList = (
      if (algorithmParamsList == null) null
      else algorithmParamsList.toSeq)
    
    APIDebugWorkflow.run(
      batch = batch,
      verbose = verbose,
      dataSourceClassOpt = noneIfNull(dataSourceClass),
      dataSourceParams = dataSourceParams,
      preparatorClassOpt = noneIfNull(preparatorClass),
      preparatorParams = preparatorParams,
      algorithmClassMapOpt = noneIfNull(scalaAlgorithmClassMap),
      algorithmParamsList = scalaAlgorithmParamsList,
      servingClassOpt = noneIfNull(servingClass),
      servingParams = servingParams,
      metricsClassOpt = noneIfNull(metricsClass),
      metricsParams = metricsParams
    )(
      JavaUtils.fakeClassTag[MU], 
      JavaUtils.fakeClassTag[MR],
      JavaUtils.fakeClassTag[MMR])

  }

  def runEngine[DP, TD, PD, Q, P, A](
    batch: String,
    verbose: Int,
    engine: Engine[TD, DP, PD, Q, P, A],
    engineParams: EngineParams) {
    runEngine(
      batch = batch,
      verbose = verbose,
      engine = engine,
      engineParams = engineParams,
      metricsClass = null,
      metricsParams = null
    )
  }
  
  def runEngine[DP, TD, PD, Q, P, A, MU, MR, MMR <: AnyRef](
      batch: String,
      verbose: Int,
      engine: Engine[TD, DP, PD, Q, P, A],
      engineParams: EngineParams,
      metricsClass
        : Class[_ <: BaseMetrics[_ <: Params, DP, Q, P, A, MU, MR, MMR]],
      metricsParams: Params) {
    run(
      batch = batch,
      verbose = verbose,
      dataSourceClass = engine.dataSourceClass,
      dataSourceParams = engineParams.dataSourceParams,
      preparatorClass = engine.preparatorClass,
      preparatorParams = engineParams.preparatorParams,
      algorithmClassMap = engine.algorithmClassMap,
      algorithmParamsList = engineParams.algorithmParamsList,
      servingClass = engine.servingClass,
      servingParams = engineParams.servingParams,
      metricsClass = metricsClass,
      metricsParams = metricsParams)

    /*
    APIDebugWorkflow.runEngine(
      batch = batch,
      verbose = verbose,
      engine = engine,
      engineParams = engineParams,
      metricsClass = metricsClass,
      metricsParams = metricsParams
    )(
      JavaUtils.fakeClassTag[MU], 
      JavaUtils.fakeClassTag[MR],
      JavaUtils.fakeClassTag[MMR])
    */
  }
}