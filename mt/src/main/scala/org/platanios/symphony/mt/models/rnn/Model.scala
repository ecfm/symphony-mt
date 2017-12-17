/* Copyright 2017, Emmanouil Antonios Platanios. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.platanios.symphony.mt.models.rnn

import org.platanios.symphony.mt.{Environment, Language, LogConfig}
import org.platanios.symphony.mt.core.hooks.PerplexityLogger
import org.platanios.symphony.mt.data.{DataConfig, Datasets, Vocabulary}
import org.platanios.symphony.mt.data.Datasets.{MTTextLinesDataset, MTTrainDataset}
import org.platanios.symphony.mt.metrics.BLEUTensorFlow
import org.platanios.symphony.mt.models.{InferConfig, TrainConfig}
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.learn
import org.platanios.tensorflow.api.learn.hooks.StepHookTrigger
import org.platanios.tensorflow.api.learn.layers.rnn.cell._
import org.platanios.tensorflow.api.learn.{EVALUATION, INFERENCE, Mode, StopCriteria}
import org.platanios.tensorflow.api.learn.layers.{Input, Layer, LayerInstance}
import org.platanios.tensorflow.api.ops.Output
import org.platanios.tensorflow.api.ops.control_flow.WhileLoopVariable
import org.platanios.tensorflow.api.ops.rnn.cell.Tuple
import org.platanios.tensorflow.api.ops.seq2seq.decoders.{BasicDecoder, BeamSearchDecoder, GooglePenalty}
import org.platanios.tensorflow.api.ops.training.optimizers.decay.ExponentialDecay
import org.platanios.tensorflow.api.types.DataType

// TODO: Add support for optimizer schedules (e.g., Adam for first 1000 steps and then SGD with a different learning rate.
// TODO: Customize evaluation metrics, hooks, etc.

/**
  * @author Emmanouil Antonios Platanios
  */
trait Model[S, SS] {
  val name         : String
  val srcLanguage  : Language
  val tgtLanguage  : Language
  val srcVocabulary: Vocabulary
  val tgtVocabulary: Vocabulary

  val srcTrainDataset: MTTextLinesDataset
  val tgtTrainDataset: MTTextLinesDataset
  val srcDevDataset : MTTextLinesDataset = null
  val tgtDevDataset : MTTextLinesDataset = null
  val srcTestDataset: MTTextLinesDataset = null
  val tgtTestDataset: MTTextLinesDataset = null

  val env        : Environment = Environment()
  val rnnConfig  : RNNConfig   = RNNConfig()
  val dataConfig : DataConfig  = DataConfig()
  val trainConfig: TrainConfig = TrainConfig()
  val inferConfig: InferConfig = InferConfig()
  val logConfig  : LogConfig   = LogConfig()

  // Create the input and the train input parts of the model.
  protected val input      = Input((INT32, INT32), (Shape(-1, -1), Shape(-1)))
  protected val trainInput = Input((INT32, INT32, INT32), (Shape(-1, -1), Shape(-1, -1), Shape(-1)))

  protected def encoder: Layer[(Output, Output), Tuple[Output, Seq[S]]]
  protected def trainDecoder: Layer[((Output, Output, Output), Tuple[Output, Seq[S]]), (Output, Output)]
  protected def inferDecoder: Layer[((Output, Output), Tuple[Output, Seq[S]]), (Output, Output)]

  protected def createTrainDataset(
      srcDataset: MTTextLinesDataset,
      tgtDataset: MTTextLinesDataset,
      batchSize: Int,
      repeat: Boolean,
      numBuckets: Int
  ): MTTrainDataset = {
    Datasets.createTrainDataset(
      srcDataset, tgtDataset, srcVocabulary.lookupTable(), tgtVocabulary.lookupTable(), dataConfig, batchSize, repeat,
      env.randomSeed)
  }

  protected val estimator: tf.learn.Estimator[
      (Tensor, Tensor), (Output, Output), (DataType, DataType), (Shape, Shape), (Output, Output),
      ((Tensor, Tensor), (Tensor, Tensor, Tensor)), ((Output, Output), (Output, Output, Output)),
      ((DataType, DataType), (DataType, DataType, DataType)), ((Shape, Shape), (Shape, Shape, Shape)),
      ((Output, Output), (Output, Output, Output))] = tf.createWithNameScope(name) {
    val model = learn.Model(
      input = input,
      layer = inferLayer,
      trainLayer = trainLayer,
      trainInput = trainInput,
      loss = lossLayer,
      optimizer = optimizer,
      clipGradients = tf.learn.ClipGradientsByGlobalNorm(trainConfig.maxGradNorm),
      colocateGradientsWithOps = trainConfig.colocateGradientsWithOps)
    val summariesDir = env.workingDir.resolve("summaries")
    val tensorBoardConfig = {
      if (trainConfig.launchTensorBoard)
        tf.learn.TensorBoardConfig(summariesDir, reloadInterval = 1)
      else
        null
    }
    var hooks = Set[tf.learn.Hook](
      // tf.learn.LossLogger(trigger = tf.learn.StepHookTrigger(1)),
      tf.learn.StepRateLogger(log = false, summaryDir = summariesDir, trigger = StepHookTrigger(100)),
      tf.learn.SummarySaver(summariesDir, StepHookTrigger(trainConfig.summarySteps)),
      tf.learn.CheckpointSaver(env.workingDir, StepHookTrigger(trainConfig.checkpointSteps)))
    if (logConfig.logLossSteps > 0)
      hooks += PerplexityLogger(log = true, trigger = StepHookTrigger(logConfig.logLossSteps))
    if (logConfig.logTrainEvalSteps > 0 && srcTrainDataset != null && tgtTrainDataset != null)
      hooks += tf.learn.Evaluator(
        log = true, summariesDir,
        () => createTrainDataset(srcTrainDataset, tgtTrainDataset, logConfig.logEvalBatchSize, repeat = false, 1),
        Seq(BLEUTensorFlow()), StepHookTrigger(logConfig.logTrainEvalSteps),
        triggerAtEnd = true, name = "TrainEvaluator")
    if (logConfig.logDevEvalSteps > 0 && srcDevDataset != null && tgtDevDataset != null)
      hooks += tf.learn.Evaluator(
        log = true, summariesDir,
        () => createTrainDataset(srcDevDataset, tgtDevDataset, logConfig.logEvalBatchSize, repeat = false, 1),
        Seq(BLEUTensorFlow()), StepHookTrigger(logConfig.logDevEvalSteps),
        triggerAtEnd = true, name = "DevEvaluator")
    if (logConfig.logTestEvalSteps > 0 && srcTestDataset != null && tgtTestDataset != null)
      hooks += tf.learn.Evaluator(
        log = true, summariesDir,
        () => createTrainDataset(srcTestDataset, tgtTestDataset, logConfig.logEvalBatchSize, repeat = false, 1),
        Seq(BLEUTensorFlow()), StepHookTrigger(logConfig.logTestEvalSteps),
        triggerAtEnd = true, name = "TestEvaluator")
    tf.learn.InMemoryEstimator(
      model, tf.learn.Configuration(Some(env.workingDir), randomSeed = env.randomSeed),
      StopCriteria(Some(trainConfig.numSteps)), hooks, tensorBoardConfig = tensorBoardConfig)
  }

  protected def trainLayer: Layer[((Output, Output), (Output, Output, Output)), (Output, Output)] = {
    new Layer[((Output, Output), (Output, Output, Output)), (Output, Output)](name) {
      override val layerType: String = "ModelTrainLayer"

      override def forward(
          input: ((Output, Output), (Output, Output, Output)),
          mode: Mode
      ): LayerInstance[((Output, Output), (Output, Output, Output)), (Output, Output)] = {
        val encLayerInstance = encoder(input._1, mode)
        val decLayerInstance = trainDecoder((input._2, encLayerInstance.output), mode)
        LayerInstance(
          input, decLayerInstance.output,
          encLayerInstance.trainableVariables ++ decLayerInstance.trainableVariables,
          encLayerInstance.nonTrainableVariables ++ decLayerInstance.nonTrainableVariables)
      }
    }
  }

  protected def inferLayer: Layer[(Output, Output), (Output, Output)] = {
    new Layer[(Output, Output), (Output, Output)](name) {
      override val layerType: String = "ModelInferLayer"

      override def forward(input: (Output, Output), mode: Mode): LayerInstance[(Output, Output), (Output, Output)] = {
        val encLayerInstance = encoder(input, mode)
        val decLayerInstance = inferDecoder((input, encLayerInstance.output), mode)
        // Make sure the outputs are of shape [batchSize, time] or [beamWidth, batchSize, time] when using beam search.
        val outputSequence = {
          if (rnnConfig.timeMajor)
            decLayerInstance.output._1.transpose()
          else if (decLayerInstance.output._1.rank == 3)
            decLayerInstance.output._1.transpose(Tensor(2, 0, 1))
          else
            decLayerInstance.output._1
        }
        LayerInstance(
          input, (outputSequence, decLayerInstance.output._2),
          encLayerInstance.trainableVariables ++ decLayerInstance.trainableVariables,
          encLayerInstance.nonTrainableVariables ++ decLayerInstance.nonTrainableVariables)
      }
    }
  }

  protected def lossLayer: Layer[((Output, Output), (Output, Output, Output)), Output] = {
    new Layer[((Output, Output), (Output, Output, Output)), Output](name) {
      override val layerType: String = "ModelLoss"

      override def forward(
          input: ((Output, Output), (Output, Output, Output)),
          mode: Mode
      ): LayerInstance[((Output, Output), (Output, Output, Output)), Output] = tf.createWithNameScope("Loss") {
        val predictedSequence = if (rnnConfig.timeMajor) input._1._1.transpose(Tensor(1, 0, 2)) else input._1._1
        val targetSequence = input._2._2
        val loss = tf.sum(tf.sequenceLoss(
          predictedSequence, targetSequence,
          weights = tf.sequenceMask(input._1._2, tf.shape(predictedSequence)(1), dataType = input._1._1.dataType),
          averageAcrossTimeSteps = false, averageAcrossBatch = true))
        tf.summary.scalar("Loss", loss)
        LayerInstance(input, loss)
      }
    }
  }

  protected def optimizer: tf.train.Optimizer = {
    val decay = ExponentialDecay(
      trainConfig.learningRateDecayRate,
      trainConfig.learningRateDecaySteps,
      staircase = true,
      trainConfig.learningRateDecayStartStep)
    trainConfig.optimizer(trainConfig.learningRateInitial, decay)
  }

  def train(stopCriteria: StopCriteria = StopCriteria(Some(trainConfig.numSteps))): Unit = {
    val trainDataset = () => createTrainDataset(
      srcTrainDataset, tgtTrainDataset, trainConfig.batchSize, repeat = true, dataConfig.numBuckets)
    estimator.train(trainDataset, stopCriteria)
  }

  protected def decode[DS, DSS](
      inputSequenceLengths: Output,
      inputState: DS,
      embeddings: Variable,
      embeddedInput: Output,
      cellInstance: CellInstance[Output, Shape, DS, DSS],
      variableFn: (String, DataType, Shape, tf.VariableInitializer) => Variable,
      isTrain: Boolean,
      mode: Mode
  )(implicit
      evS: WhileLoopVariable.Aux[DS, DSS]
  ): (Output, Output) = {
    val outputWeights = variableFn(
      "OutWeights", embeddings.dataType, Shape(cellInstance.cell.outputShape(-1), tgtVocabulary.size),
      tf.RandomUniformInitializer(-0.1f, 0.1f))
    val outputLayer = (logits: Output) => tf.linear(logits, outputWeights.value)
    if (isTrain) {
      val helper = BasicDecoder.TrainingHelper(embeddedInput, inputSequenceLengths, rnnConfig.timeMajor)
      val decoder = BasicDecoder(cellInstance.cell, inputState, helper, outputLayer)
      val tuple = decoder.decode(
        outputTimeMajor = rnnConfig.timeMajor, parallelIterations = rnnConfig.parallelIterations,
        swapMemory = rnnConfig.swapMemory)
      val lengths = tuple._3
      mode match {
        case INFERENCE | EVALUATION => (tuple._1.sample, lengths)
        case _ => (tuple._1.rnnOutput, lengths)
      }
    } else {
      val embeddingFn = (o: Output) => tf.embeddingLookup(embeddings, if (rnnConfig.timeMajor) o.transpose() else o)
      val tgtVocabLookupTable = tgtVocabulary.lookupTable()
      val tgtBosID = tgtVocabLookupTable.lookup(tf.constant(dataConfig.beginOfSequenceToken)).cast(INT32)
      val tgtEosID = tgtVocabLookupTable.lookup(tf.constant(dataConfig.endOfSequenceToken)).cast(INT32)
      if (inferConfig.beamWidth > 1) {
        val decoder = BeamSearchDecoder(
          cellInstance.cell, inputState,
          embeddingFn, tf.fill(INT32, tf.shape(inputSequenceLengths))(tgtBosID), tgtEosID, inferConfig.beamWidth,
          GooglePenalty(inferConfig.lengthPenaltyWeight), outputLayer)
        val tuple = decoder.decode(
          outputTimeMajor = rnnConfig.timeMajor,
          maximumIterations = inferMaxLength(tf.max(inputSequenceLengths)),
          parallelIterations = rnnConfig.parallelIterations, swapMemory = rnnConfig.swapMemory)
        (tuple._1.predictedIDs(---, 0), tuple._3(---, 0).cast(INT32))
      } else {
        val decHelper = BasicDecoder.GreedyEmbeddingHelper[DS](
          embeddingFn, tf.fill(INT32, tf.shape(inputSequenceLengths))(tgtBosID), tgtEosID)
        val decoder = BasicDecoder(cellInstance.cell, inputState, decHelper, outputLayer)
        val tuple = decoder.decode(
          outputTimeMajor = rnnConfig.timeMajor,
          maximumIterations = inferMaxLength(tf.max(inputSequenceLengths)),
          parallelIterations = rnnConfig.parallelIterations, swapMemory = rnnConfig.swapMemory)
        (tuple._1.sample, tuple._3)
      }
    }
  }

  /** Returns the maximum sequence length to consider while decoding during inference, given the provided source
    * sequence length. */
  protected def inferMaxLength(srcLength: Output): Output = {
    if (dataConfig.tgtMaxLength != -1)
      tf.constant(dataConfig.tgtMaxLength)
    else
      tf.round(tf.max(srcLength) * inferConfig.decoderMaxLengthFactor).cast(INT32)
  }
}

object Model {
  private[this] def device(layerIndex: Int, numGPUs: Int = 0): String = {
    if (numGPUs == 0)
      "/device:CPU:0"
    else
      s"/device:GPU:${layerIndex % numGPUs}"
  }

  private[rnn] def cell[S, SS](
      cellCreator: Cell[S, SS],
      numUnits: Int,
      dataType: DataType,
      dropout: Option[Float] = None,
      residualFn: Option[(Output, Output) => Output] = None,
      device: Option[String] = None,
      seed: Option[Int] = None,
      name: String
  )(implicit
      evS: WhileLoopVariable.Aux[S, SS],
      evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
  ): tf.learn.RNNCell[Output, Shape, S, SS] = tf.learn.nameScope(name) {
    var createdCell = cellCreator.create(name, numUnits, dataType)
    createdCell = dropout.map(p => DropoutWrapper("Dropout", createdCell, 1.0f - p, seed = seed)).getOrElse(createdCell)
    createdCell = residualFn.map(ResidualWrapper("Residual", createdCell, _)).getOrElse(createdCell)
    createdCell = device.map(DeviceWrapper("Device", createdCell, _)).getOrElse(createdCell)
    createdCell
  }

  private[rnn] def cells[S, SS](
      cellCreator: Cell[S, SS],
      numUnits: Int,
      dataType: DataType,
      numLayers: Int,
      numResidualLayers: Int,
      dropout: Option[Float] = None,
      residualFn: Option[(Output, Output) => Output] = Some((input: Output, output: Output) => input + output),
      baseGPU: Int = 0,
      numGPUs: Int = 0,
      seed: Option[Int] = None,
      name: String
  )(implicit
      evS: WhileLoopVariable.Aux[S, SS],
      evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
  ): Seq[tf.learn.RNNCell[Output, Shape, S, SS]] = tf.learn.nameScope(name) {
    (0 until numLayers).map(i => {
      cell(
        cellCreator, numUnits, dataType, dropout, if (i >= numLayers - numResidualLayers) residualFn else None,
        Some(device(i + baseGPU, numGPUs)), seed, s"Cell$i")
    })
  }

  private[rnn] def multiCell[S, SS](
      cellCreator: Cell[S, SS],
      numUnits: Int,
      dataType: DataType,
      numLayers: Int,
      numResidualLayers: Int,
      dropout: Option[Float] = None,
      residualFn: Option[(Output, Output) => Output] = Some((input: Output, output: Output) => input + output),
      baseGPU: Int = 0,
      numGPUs: Int = 0,
      seed: Option[Int] = None,
      name: String,
  )(implicit
      evS: WhileLoopVariable.Aux[S, SS],
      evSDropout: ops.rnn.cell.DropoutWrapper.Supported[S]
  ): tf.learn.RNNCell[Output, Shape, Seq[S], Seq[SS]] = {
    MultiRNNCell(name, cells(
      cellCreator, numUnits, dataType, numLayers, numResidualLayers, dropout,
      residualFn, baseGPU, numGPUs, seed, name))
  }
}
