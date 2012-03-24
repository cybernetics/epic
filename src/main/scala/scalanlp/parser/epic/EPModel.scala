package scalanlp.parser.epic

import collection.mutable.ArrayBuffer
import scalanlp.inference.{Factor, ExpectationPropagation}
import scalala.tensor.dense.DenseVector
import scalanlp.optimize.FirstOrderMinimizer.OptParams
import scalanlp.parser.ParseEval.Statistics
import scalanlp.parser.Grammar._
import scalala.library.Library
import scalanlp.parser.ParseChart._
import scalanlp.parser._
import features.Feature
import projections.GrammarProjections
import scalala.library.Library._
import scalanlp.optimize.{BatchDiffFunction, FirstOrderMinimizer, CachedBatchDiffFunction}
import scalanlp.util.Index
import java.io.File
import scalala.tensor.{Counter, ::}

case class ComponentFeature(index: Int, feature: Feature) extends Feature
import scalanlp.util._

object EPModel {
  type CompatibleModel[Datum, Augment] = Model[Datum] { type Inference <: ProjectableInference[Datum,Augment]}
}


/**
 * 
 * @author dlwh
 */
class EPModel[Datum, Augment](maxEPIter: Int, initFeatureValue: Feature=>Option[Double],
                              models: EPModel.CompatibleModel[Datum,Augment]*)(implicit aIsFactor: Augment<:<Factor[Augment]) extends Model[Datum] {
  type ExpectedCounts = EPExpectedCounts
  type Inference = EPInference[Datum, Augment]

  val featureIndex:Index[Feature] = {
    val index = Index[Feature]()
    for( (m,i) <- models.zipWithIndex; f <- m.featureIndex) index.index(ComponentFeature(i,f))
    index
  }

  override def initialValueForFeature(f: Feature) = initFeatureValue(f) getOrElse {
    f match {
      case ComponentFeature(m,ff) => models(m).initialValueForFeature(ff)
      case _ => 0.0
    }
  }

  private val offsets = models.map(_.numFeatures).unfold(0)(_ + _)

  def emptyCounts = {
    val counts = for( (m: Model[Datum] { type Inference <: ProjectableInference[Datum,Augment]}) <- models.toIndexedSeq) yield m.emptyCounts
    EPExpectedCounts(0.0,counts)
  }

  def expectedCountsToObjective(ecounts: EPModel[Datum, Augment]#ExpectedCounts) = {
    val vectors = for( (m,e) <- models zip ecounts.counts) yield m.expectedCountsToObjective(e.asInstanceOf[m.ExpectedCounts])._2
    ecounts.loss -> DenseVector.vertcat(vectors:_*)
  }

  def inferenceFromWeights(weights: DenseVector[Double]) = {
    val allWeights = partitionWeights(weights)
    val builders = ArrayBuffer.tabulate(models.length) { i =>
      models(i).inferenceFromWeights(allWeights(i))
    }
    new EPInference(builders, maxEPIter)
  }

  private def partitionWeights(weights: DenseVector[Double]): Array[DenseVector[Double]] = {
    Array.tabulate(models.length)(m => projectWeights(weights, m))
  }

  private def projectWeights(weights: DenseVector[Double], modelIndex: Int) = {
    val result = DenseVector.zeros[Double](models(modelIndex).numFeatures)
    for(i <- 0 until result.size) {
      result(i) = weights(i + offsets(modelIndex))
    }
    result
  }

  override def cacheFeatureWeights(weights: DenseVector[Double], prefix: String) {
    super.cacheFeatureWeights(weights, prefix)
    for( ((w,m),i) <- (partitionWeights(weights) zip models).zipWithIndex) {
      m.cacheFeatureWeights(w,prefix+"-model-"+i)
    }
  }
}

case class EPExpectedCounts(var loss: Double, counts: IndexedSeq[ExpectedCounts[_]]) extends ExpectedCounts[EPExpectedCounts] {
  def +=(other: EPExpectedCounts) = {
    for( (t,u) <- counts zip other.counts) {
      t.asInstanceOf[{ def +=(e: ExpectedCounts[_]):ExpectedCounts[_]}] += u
    }
    this.loss += other.loss
    this
  }

  def -=(other: EPExpectedCounts) = {
    for( (t,u) <- counts zip other.counts) {
      t.asInstanceOf[{ def -=(e: ExpectedCounts[_]):ExpectedCounts[_]}] -= u
    }
    this.loss -= other.loss
    this
  }
}

case class EPInference[Datum, Augment](inferences: IndexedSeq[ProjectableInference[Datum, Augment]],
                                        maxEPIter: Int)(implicit aIsFactor: Augment<:<Factor[Augment]) extends AugmentableInference[Datum, Augment] {
  type ExpectedCounts = EPExpectedCounts

  def baseAugment(v: Datum) = inferences(0).baseAugment(v)

  // assume we don't need gold  to do EP, at least for now
  def goldCounts(value: Datum, augment: Augment) = {
    val counts = inferences.map(_.goldCounts(value,augment))
    EPExpectedCounts(counts.foldLeft(0.0){_ + _.loss}, counts)
  }

  def getMarginals(datum: Datum, augment: Augment) = {
    val marginals = ArrayBuffer.fill(inferences.length)(null.asInstanceOf[ProjectableInference[Datum, Augment]#Marginal])
    def project(q: Augment, i: Int) = {
      val inf = inferences(i)
      val (marg,contributionToLikelihood) = inf.marginal(datum,q)
      val newAugment = inf.project(datum,marg,q)
      marginals(i) = marg
      newAugment -> contributionToLikelihood
    }
    val ep = new ExpectationPropagation(project _)

    var state : ep.State = null
    val iterates = ep.inference(augment, 0 until inferences.length, inferences.map(_.baseAugment(datum)))
    var iter = 0
    var converged = false
    while(!converged && iter < maxEPIter && iterates.hasNext) {
      val s = iterates.next()
      if(state != null) {
        converged = (s.logPartition - state.logPartition).abs/math.max(s.logPartition,state.logPartition) < 1E-4
      }
      iter += 1
      state = s
    }
    print(iter +" ")

    (state.logPartition, state.q, marginals, state.f_~)
  }

  def guessCounts(datum: Datum, augment: Augment) = {
    val (partition, finalAugment, marginals, f_~) = getMarginals(datum,augment)

    val finalCounts = for( ((inf,f_~),i) <- (inferences zip f_~).zipWithIndex) yield {
      val marg = marginals(i)
      val augment = f_~
      inf.guessCountsFromMarginals(datum, marg.asInstanceOf[inf.Marginal], augment)
    }

    EPExpectedCounts(partition, finalCounts)
  }
}

case class EPParams(iterations: Int= 5, pruningThreshold: Double = -15)

case class EPParserModelFactory(ep: EPParams,
                                parser: ParserParams.BaseParser[String],
                                // I realy need ot figure out how to get this into my config language...
                                model1: ParserModelFactory[String, String] = null,
                                model2: ParserModelFactory[String, String] = null,
                                model3: ParserModelFactory[String, String] = null,
                                model4: ParserModelFactory[String, String] = null,
                                model5: ParserModelFactory[String, String] = null,
                                oldWeights: File = null) extends ModelFactory[TreeInstance[String, String]] {
  type MyModel = EPModel[TreeInstance[String,String], SpanScorerFactor[String,String]] with EPParserExtractor[String,String]

  def make(train: IndexedSeq[TreeInstance[String, String]]) = {
    val (initLexicon,initBinaries,initUnaries) = GenerativeParser.extractCounts(train)

    val xbarParser = parser.optParser getOrElse {
      val grammar = Grammar(Library.logAndNormalizeRows(initBinaries),Library.logAndNormalizeRows(initUnaries));
      val lexicon = new SimpleLexicon(initLexicon);
      new CKYChartBuilder[LogProbabilityParseChart,String,String]("",lexicon,grammar,ParseChart.logProb);
    }

    type ModelType = Model[TreeInstance[String,String]] { type Inference <: ProjectableInference[TreeInstance[String,String],SpanScorerFactor[String, String]]}
    val models = Seq(model1,model2,model3,model4,model5).filterNot(_ eq null) map { model =>
      val m1 = model.make(train)
      val wrappedM1 = new ParserEPComponent[String,m1.L2,String](m1, new AnchoredRuleApproximator(xbarParser, ep.pruningThreshold))
      wrappedM1:ModelType
    }

    val featureCounter = if(oldWeights ne null) {
      readObject[Counter[Feature,Double]](oldWeights)
    } else {
      Counter[Feature,Double]()
    }

    new EPModel(ep.iterations, {featureCounter.get(_)}, models:_*) with EPParserExtractor[String, String] with Serializable {
      val zeroParser = SimpleChartParser(new CKYChartBuilder(xbarParser.root,
        new ZeroLexicon(xbarParser.lexicon),
        Grammar.zero(xbarParser.grammar),ParseChart.logProb))
    }
  }
}

object EPPipeline extends ParserPipeline {
  case class Params(modelFactory: EPParserModelFactory,
                    opt: OptParams,
                    numStates: Int= 2,
                    iterationsPerEval: Int = 50,
                    maxIterations: Int = 1001,
                    iterPerValidate: Int = 10,
                    randomize: Boolean = true,
                    ep: EPParams);
  protected val paramManifest = manifest[Params]


  def split(x: String, numStates: Int) = {
    if(x.isEmpty) Seq((x,0))
    else for(i <- 0 until numStates) yield (x,i)
  }

  def unsplit(x: (String,Int)) = x._1

  def trainParser(trainTrees: IndexedSeq[TreeInstance[String, String]], validate: (Parser[String, String]) => Statistics, params: Params) = {
    import params._

    val epModel = modelFactory.make(trainTrees)

    val obj = new ModelObjective(epModel,trainTrees)
    val cachedObj = new CachedBatchDiffFunction(obj)
    val init = obj.initialWeightVector(randomize)

    type OptState = FirstOrderMinimizer[DenseVector[Double],BatchDiffFunction[DenseVector[Double]]]#State
    def evalAndCache(pair: (OptState,Int) ) {
      val (state,iter) = pair
      val weights = state.x
      if(iter % iterPerValidate == 0) {
        println("Validating...")
        val parser = epModel.extractParser(state.x)
        println(validate(parser))
      }
    }

    for( (state,iter) <- params.opt.iterations(cachedObj,init).take(maxIterations).zipWithIndex.tee(evalAndCache _)
         if iter != 0 && iter % iterationsPerEval == 0) yield try {
      val parser = epModel.extractParser(state.x)
      ("EP-" + iter.toString,parser)
    } catch {
      case e => println(e);e.printStackTrace(); throw e
    }
  }
}