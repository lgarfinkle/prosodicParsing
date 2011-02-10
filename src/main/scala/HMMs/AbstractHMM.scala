package ProsodicParsing.HMMs

import ProsodicParsing.types._
import cc.mallet.grmm.types._
import cc.mallet.grmm.inference.ForwardBackwardInferencer
import cc.mallet.grmm.inference.JunctionTreeInferencer
import cc.mallet.grmm.util.Models
import ProsodicParsing.util.Util

abstract class AbstractHMM[HiddenType<:HiddenLabel,ObservedType<:ObservedLabel](
  hiddenStateTypesSet:Set[HiddenType],
  observationTypesSet:Set[ObservedType]
) {
  //var matrices:Set[AbstractDistribution]
  //var parameters:Parameters
  //type Dist<:AbstractDistribution
  var parameters:List[AbstractDistribution]

  val observationTypes = observationTypesSet.toList.sortWith( (a,b) => a < b )
  val hiddenStateTypes = hiddenStateTypesSet.toList.sortWith( (a,b) => a < b )

  val numHiddenStates = hiddenStateTypes.size

  def randomize( n:Int ) {
    parameters.foreach( _.randomize( n ) )
  }

  def normalize {
    parameters.foreach( _.normalize )
  }

  //type P<:Parameters
  def setParams( params:Parameters ):Unit

  var hmm = new DynamicBayesNet(0)

  def buildSlicedHMM( tokens:List[ObservedType] ):Unit

  def buildHMM( tokens:List[ObservedType] ):Unit


  var observations:Array[Variable] = Array()
  var hiddenVariables:Array[Variable] = Array()

  def generateObservationSequence( tokens:List[ObservedType] ):Assignment

  val inferencer = new ForwardBackwardInferencer()
  def generalProbability( tokens:List[ObservedType] ) = {
    buildHMM( tokens )

    val observationSequence = generateObservationSequence( tokens )

    inferencer.queryLogForwardBackward( hmm, observationSequence )
  }

  def computePartialCounts( sequence:List[ObservedType] ):PartialCounts

  def assignmentToViterbiString( maxAssn:Assignment ):List[HiddenType]

  var stringLength = 0

  def argmax( string:List[ObservedType] ) = {
    buildSlicedHMM( string )
    val maxAssn = Models.bestAssignment( hmm,  JunctionTreeInferencer.createForMaxProduct() )
    assignmentToViterbiString( maxAssn )
  }

  // def argmax( corpus:List[List[ObservedType]] ) =
  //   corpus.map{ string =>
  //     buildSlicedHMM( string )
  //     val maxAssn = Models.viterbi( hmm,  ForwardBackwardInferencer.createForMaxProduct() )
  //     assignmentToViterbiString( maxAssn )
  //   }

  def reestimate( corpus: List[List[ObservedType]] ):Double
}

