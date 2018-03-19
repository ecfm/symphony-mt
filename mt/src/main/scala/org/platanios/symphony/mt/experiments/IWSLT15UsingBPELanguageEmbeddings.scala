/* Copyright 2017-18, Emmanouil Antonios Platanios. All Rights Reserved.
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

package org.platanios.symphony.mt.experiments

import org.platanios.symphony.mt.{Environment, Language}
import org.platanios.symphony.mt.Language.{english, vietnamese}
import org.platanios.symphony.mt.data._
import org.platanios.symphony.mt.data.loaders.IWSLT15DatasetLoader
import org.platanios.symphony.mt.data.processors.{MosesCleaner, NoTokenizer}
import org.platanios.symphony.mt.models.rnn._
import org.platanios.symphony.mt.models.rnn.attention.LuongRNNAttention
import org.platanios.symphony.mt.models.{LanguageEmbeddingsParameterManager, Model, RNNModel}
import org.platanios.symphony.mt.vocabulary.BPEVocabularyGenerator
import org.platanios.tensorflow.api._

import java.nio.file.{Path, Paths}

/**
  * @author Emmanouil Antonios Platanios
  */
object IWSLT15UsingBPELanguageEmbeddings extends App {
  val workingDir: Path = Paths.get("temp").resolve("iwslt15-bpe-rnn-language-embeddings")

  val srcLanguage: Language = english
  val tgtLanguage: Language = vietnamese

  val dataConfig = DataConfig(
    workingDir = Paths.get("temp").resolve("data"),
    loaderTokenizer = NoTokenizer,
    loaderCleaner = MosesCleaner(1, 80),
    loaderVocab = GeneratedVocabulary(BPEVocabularyGenerator(10000, replaceExisting = false)),
    numBuckets = 5,
    srcMaxLength = 80,
    tgtMaxLength = 80)

  val dataset: FileParallelDataset = IWSLT15DatasetLoader(srcLanguage, tgtLanguage, dataConfig).load()

  val env = Environment(
    workingDir = workingDir.resolve(s"${srcLanguage.abbreviation}-${tgtLanguage.abbreviation}"),
    allowSoftPlacement = true,
    logDevicePlacement = false,
    gpuAllowMemoryGrowth = false,
    useXLA = false,
    numGPUs = 1,
    parallelIterations = 32,
    swapMemory = true,
    randomSeed = Some(10))

  val optConfig = Model.OptConfig(
    maxGradNorm = 100.0f,
    optimizer = tf.train.AMSGrad(learningRateSummaryTag = "LearningRate"))

  val logConfig = Model.LogConfig(logLossSteps = 100)

  val model = RNNModel(
    name = "Model",
    languages = Seq(srcLanguage -> dataset.vocabulary(srcLanguage), tgtLanguage -> dataset.vocabulary(tgtLanguage)),
    dataConfig = dataConfig,
    config = RNNModel.Config(
      env,
      LanguageEmbeddingsParameterManager(
        languageEmbeddingsSize = 64,
        wordEmbeddingsSize = 512),
      BidirectionalRNNEncoder(
        cell = BasicLSTM(forgetBias = 1.0f),
        numUnits = 512,
        numLayers = 2,
        residual = false,
        dropout = Some(0.2f)),
      UnidirectionalRNNDecoder(
        cell = BasicLSTM(forgetBias = 1.0f),
        numUnits = 512,
        numLayers = 2,
        residual = false,
        dropout = Some(0.2f),
        attention = Some(LuongRNNAttention(scaled = true)),
        outputAttention = true),
      labelSmoothing = 0.1f,
      timeMajor = true,
      beamWidth = 10),
    optConfig = optConfig,
    logConfig = logConfig,
    // TODO: !!! Find a way to set the number of buckets to 1.
    evalDatasets = Seq(
      ("IWSLT15/dev", dataset.filterTypes(Dev).filterLanguages(srcLanguage, tgtLanguage)),
      ("IWSLT15/test", dataset.filterTypes(Test).filterLanguages(srcLanguage, tgtLanguage))))

  model.train(dataset.filterTypes(Train), tf.learn.StopCriteria.steps(120000))

  // val evaluator = BilingualEvaluator(Seq(BLEU()), srcLanguage, tgtLanguage, dataset.filterTypes(Test))
  // println(evaluator.evaluate(model).values.head.scalar.asInstanceOf[Float])
}