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

package org.platanios.symphony.mt.models.parameters

import org.platanios.symphony.mt.models._
import org.platanios.symphony.mt.vocabulary.Vocabulary
import org.platanios.symphony.mt.{Environment, Language}
import org.platanios.tensorflow.api._
import org.platanios.tensorflow.api.core.exception.InvalidDataTypeException
import org.platanios.tensorflow.api.ops.{FunctionGraph, Op}

import scala.collection.mutable

/**
  * @author Emmanouil Antonios Platanios
  */
class ParameterManager protected (
    val wordEmbeddingsType: WordEmbeddingsType,
    val variableInitializer: tf.VariableInitializer = null
) {
  protected var environment  : Environment                 = _
  protected var deviceManager: Option[DeviceManager]       = None
  protected var languages    : Seq[(Language, Vocabulary)] = _

  protected val languageIds                : mutable.Map[Graph, Seq[Output]] = mutable.Map.empty
  protected val stringToIndexLookupTables  : mutable.Map[Graph, Output]      = mutable.Map.empty
  protected val stringToIndexLookupDefaults: mutable.Map[Graph, Output]      = mutable.Map.empty
  protected val indexToStringLookupTables  : mutable.Map[Graph, Output]      = mutable.Map.empty
  protected val indexToStringLookupDefaults: mutable.Map[Graph, Output]      = mutable.Map.empty
  protected val wordEmbeddings             : mutable.Map[Graph, Seq[Output]] = mutable.Map.empty

  protected val projectionsToWords: mutable.Map[Graph, mutable.Map[Int, Seq[Output]]] = mutable.Map.empty

  protected var context: Option[(Output, Output)] = None

  def setEnvironment(environment: Environment): Unit = this.environment = environment
  def setDeviceManager(deviceManager: DeviceManager): Unit = this.deviceManager = Some(deviceManager)

  protected def currentGraph: Graph = {
    var graph = tf.currentGraph
    while (graph.isInstanceOf[FunctionGraph])
      graph = graph.asInstanceOf[FunctionGraph].outerGraph
    graph
  }

  protected def removeGraph(graph: Graph): Unit = {
    languageIds -= graph
    stringToIndexLookupTables -= graph
    stringToIndexLookupDefaults -= graph
    indexToStringLookupTables -= graph
    indexToStringLookupDefaults -= graph
    wordEmbeddings -= graph
    projectionsToWords -= graph
  }

  def initialize(languages: Seq[(Language, Vocabulary)]): Unit = {
    tf.variableScope("ParameterManager") {
      languageIds.keys.filter(_.isClosed).foreach(removeGraph)
      this.languages = languages
      val graph = currentGraph
      if (!languageIds.contains(graph)) {
        languageIds += graph -> tf.variableScope("LanguageIDs") {
          languages.map(_._1).zipWithIndex.map(l => tf.constant(l._2, name = l._1.name))
        }

        tf.variableScope("StringToIndexLookupTables") {
          stringToIndexLookupTables += graph -> wordEmbeddingsType.createStringToIndexLookupTable(languages)
          stringToIndexLookupDefaults += graph -> tf.constant(Vocabulary.UNKNOWN_TOKEN_ID, INT64, name = "Default")
        }

        tf.variableScope("IndexToStringLookupTables") {
          indexToStringLookupTables += graph -> wordEmbeddingsType.createIndexToStringLookupTable(languages)
          indexToStringLookupDefaults += graph -> tf.constant(Vocabulary.UNKNOWN_TOKEN, STRING, name = "Default")
        }

        wordEmbeddings += graph -> tf.variableScope("WordEmbeddings") {
          wordEmbeddingsType.createWordEmbeddings(languages)
        }
      }
    }
  }

  def stringToIndexLookup(languageId: Output): Output => Output = (keys: Output) => {
    tf.variableScope("ParameterManager/StringToIndexLookupTables") {
      val graph = currentGraph
      val handle = wordEmbeddingsType.lookupTable(stringToIndexLookupTables(graph), languageId)
      ParameterManager.lookup(
        handle = handle,
        keys = keys,
        defaultValue = stringToIndexLookupDefaults(graph))
    }
  }

  def indexToStringLookup(languageId: Output): Output => Output = (keys: Output) => {
    tf.variableScope("ParameterManager/IndexToStringLookupTables") {
      val graph = currentGraph
      val handle = wordEmbeddingsType.lookupTable(indexToStringLookupTables(graph), languageId)
      ParameterManager.lookup(
        handle = handle,
        keys = keys,
        defaultValue = indexToStringLookupDefaults(graph))
    }
  }

  def wordEmbeddings(languageId: Output): Output => Output = (keys: Output) => {
    tf.variableScope("ParameterManager/WordEmbeddings") {
      val graph = currentGraph
      wordEmbeddingsType.embeddingLookup(wordEmbeddings(graph), languageIds(graph), languageId, keys)
    }
  }

  def getContext: Option[(Output, Output)] = this.context
  def setContext(context: (Output, Output)): Unit = this.context = Some(context)
  def resetContext(): Unit = this.context = None

  def get(
      name: String,
      dataType: DataType,
      shape: Shape,
      variableInitializer: tf.VariableInitializer = variableInitializer,
      variableReuse: tf.VariableReuse = tf.ReuseOrCreateNewVariable
  )(implicit stage: Stage): Output = {
    tf.variableScope("ParameterManager") {
      tf.variable(name, dataType, shape, initializer = variableInitializer, reuse = variableReuse).value
    }
  }

  def getProjectionToWords(inputSize: Int, languageId: Output): Output = {
    tf.variableScope("ParameterManager/ProjectionToWords") {
      val graph = currentGraph
      wordEmbeddingsType.projectionToWords(
        languages,
        languageIds(graph),
        projectionsToWords.getOrElseUpdate(graph, mutable.HashMap.empty),
        inputSize,
        languageId)
    }
  }

  def postprocessEmbeddedSequences(
      srcLanguage: Output,
      tgtLanguage: Output,
      srcSequences: Output,
      srcSequenceLengths: Output
  ): (Output, Output) = {
    (srcSequences, srcSequenceLengths)
  }
}

object ParameterManager {
  def apply(
      wordEmbeddingsType: WordEmbeddingsType,
      variableInitializer: tf.VariableInitializer = null
  ): ParameterManager = {
    new ParameterManager(wordEmbeddingsType, variableInitializer)
  }

  /** Creates an op that looks up the provided keys in the lookup table referred to by `handle` and returns the
    * corresponding values.
    *
    * @param  handle `RESOURCE` tensor containing a handle to the lookup table.
    * @param  keys   Tensor containing the keys to look up.
    * @param  name   Name for the created op.
    * @return Created op output.
    * @throws InvalidDataTypeException If the provided keys data types does not match the keys data type of this table.
    */
  @throws[InvalidDataTypeException]
  private[ParameterManager] def lookup(
      handle: Output,
      keys: Output,
      defaultValue: Output,
      name: String = "Lookup"
  ): Output = tf.createWithNameScope(name) {
    val values = Op.Builder("LookupTableFindV2", name)
        .addInput(handle)
        .addInput(keys)
        .addInput(defaultValue)
        .build().outputs(0)
    values.setShape(keys.shape)
    values
  }
}
