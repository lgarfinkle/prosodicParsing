package ProsodicParsing.HMMs

import ProsodicParsing.types._
import cc.mallet.types.{LabelAlphabet,LabelSequence}
import cc.mallet.grmm._
import cc.mallet.grmm.types._
import cc.mallet.grmm.inference.JunctionTreeInferencer
import scala.collection.immutable.{HashMap,HashSet}
import scala.collection.mutable.{HashMap => MHashMap}

class PlainHMM( hiddenStateTypes:Set[HiddenState], observationTypes:Set[Observation] ) {
  val observationAlphabet = new LabelAlphabet()
  observationTypes.foreach( observationAlphabet.lookupIndex( _, true ) )

  val hiddenStateAlphabet = new LabelAlphabet()
  hiddenStateTypes.foreach( hiddenStateAlphabet.lookupIndex( _, true ) )

  val numHiddenStates = hiddenStateTypes.size
  //val hiddenStateTypes = (0 to (numHiddenStates-1)) map ( "Q_" + _ )

  object TransitionMatrix extends ConditionalProbabilityDistribution[HiddenState,HiddenState] {
    // For now we'll initialize to a uniform transition matrix and define a
    // randomize method for people to have a random initialization whenever they
    // like
    var cpt = HashMap(
      hiddenStateTypes.map( fromStateName =>
          fromStateName -> (
            HashMap(
              hiddenStateTypes.map( toStateName =>
                toStateName -> 1D/numHiddenStates ).toSeq: _*
            )
          )
        ).toSeq: _*
      )
  }

  object EmissionMatrix extends ConditionalProbabilityDistribution[HiddenState,Observation] {
    var cpt = HashMap(
      hiddenStateTypes.map( fromStateName =>
          fromStateName -> (
            HashMap(
              observationTypes.map( toStateName =>
                toStateName -> 1D/observationTypes.size 
              ).toSeq: _*
            )
          )
        ).toSeq: _*
      )
  }

  object InitialStateProbabilities extends ProbabilityDistribution[HiddenState] {
    var pt = HashMap(
      hiddenStateTypes.map( thisStateName =>
        thisStateName -> 1D/hiddenStateTypes.size
      ).toSeq: _*
    )
  }

  def setTransitionMatrix( newTransitions:HashMap[HiddenState,HashMap[HiddenState,Double]] ) {
    TransitionMatrix.setCPT( newTransitions )
  }

  def setEmissionMatrix( newEmissions:HashMap[HiddenState,HashMap[Observation,Double]] ) {
    EmissionMatrix.setCPT( newEmissions )
  }

  def setInitialProbs( newInitialProbs:HashMap[HiddenState,Double] ) {
    InitialStateProbabilities.setPT( newInitialProbs )
  }

  def randomize(n:Int) {
    TransitionMatrix.randomize(n)
    EmissionMatrix.randomize(n)
  }

  var hmm = new DirectedModel()
  var hiddenVariables:Array[Variable] = Array()
  var observations:Array[Variable] = Array()
  def buildHMM( tokens:List[Observation] ) {
    // clear hmm this way; hmm.clear() breaks something.
    hmm = new DirectedModel()


    hiddenVariables = Array.tabulate(tokens.size)( _ => new Variable( hiddenStateAlphabet ) )
    observations = Array.tabulate(tokens.size)( _ => new Variable( observationAlphabet ) )


    ( 0 to tokens.size-1 ) foreach{ i =>
      hiddenVariables(i).setLabel("hidden."+i)
      observations(i).setLabel("observed."+i)
    }

    /*
    println( "tokens: " + tokens.mkString( "", " ", "") )
    println( "hiddenVariables: " + hiddenVariables.mkString( "", " ", "") )
    println( "observations: " + observations.mkString( "", " ", "") )
    */


    // state transitions
    ( 1 to (tokens.size-1) ) foreach{ i =>
      hmm.addFactor(
        new CPT(
          new TableFactor(
            Array( hiddenVariables(i-1), hiddenVariables(i) ),
              TransitionMatrix.toArray
          ),
          hiddenVariables(i)
        )
      )
    }

    // emissions
    ( 0 to tokens.size-1 ) foreach { i =>
      val thisObservation = new Assignment(
        observations(i) ,
        observationAlphabet.lookupIndex( tokens(i) )
      )

      hmm.addFactor(
        new CPT(
          new TableFactor(
            Array( hiddenVariables(i), observations(i) ),
            EmissionMatrix.toArray
          ),
          observations(i),
          thisObservation
        )
      )
    }
  }

  def computePartialCounts( sequence:List[Observation] ) = {
    buildHMM( sequence )

    val inferencer = new JunctionTreeInferencer()
    inferencer.computeMarginals( hmm )

    // val totalProb = inferencer.lookupJunctionTree().clusterPotentialsArray().reduceLeft( (b,a) => a.multiply(b) ).sum

    def logSpaceMultiplication( a:Array[Double], b:Array[Double] ) = (a zip b ).map{ case( l, r ) =>
      l + r
    }
      // ( logSpaceArray zip factor.asTable().toLogValueArray ).map{ case (a, b) =>
      //   a + b
      // }
    val totalProb =
    inferencer.lookupJunctionTree().clusterPotentialsArray().map{_.asTable().toLogValueArray}.reduceLeft(
    logSpaceMultiplication).map{ math.exp(_) }.sum
    
    val stateCounts = MHashMap(
      hiddenStateTypes.map{ _ -> 0D }.toSeq:_*
    )

    val emissionCounts = MHashMap(
      hiddenStateTypes.map{ q =>
        q -> MHashMap(
          observationTypes.map{ obs =>
            obs -> 0D
          }.toSeq:_*
        )
      }.toSeq:_*
    )
    
          /*
          ( 0 to (hiddenVariables.size-1) ) foreach { hiddenVarIndex =>
            val hiddenVar = hiddenVariables( hiddenVarIndex )
            val thisMarginal = inferencer.lookupMarginal( hiddenVar )
            hiddenStateTypes foreach { q =>
              val thisCount = hiddenStateAlphabet.lookupIndex( q )
              //stateCounts( q ) += thisMarginal.value( new Assignment( hiddenVar , thisCount ) )
              emissionCounts( q )( sequence(hiddenVarIndex) ) +=
                thisMarginal.value( new Assignment( hiddenVar , thisCount ) )
            }
          }
          */
    
    val transitionCounts = MHashMap(
      hiddenStateTypes.map{ qFrom =>
        qFrom -> MHashMap(
          hiddenStateTypes.map{ qTo =>
            qTo -> 0D
          }.toSeq:_*
        )
      }.toSeq:_*
    )

    // loop through all transitions
    //( hiddenVariables.init zip hiddenVariables.tail ) foreach { case ( fromVar, toVar ) =>
    ( 0 to (hiddenVariables.size-2) ) foreach { i =>

      val (fromVar, toVar) = (hiddenVariables(i), hiddenVariables(i+1) )

      hiddenStateTypes foreach { qFrom =>
        hiddenStateTypes foreach { qTo =>

          val thisTransition = inferencer.lookupMarginal( new HashVarSet( Array( fromVar, toVar ) ) )

          val thisTransitionCount = thisTransition.value(
            new Assignment(
              Array( fromVar, toVar ),
              Array(
                hiddenStateAlphabet.lookupIndex( qFrom ),
                hiddenStateAlphabet.lookupIndex( qTo )
              )
            )
          )

          transitionCounts(qFrom)(qTo) += thisTransitionCount

          stateCounts(qFrom) += thisTransitionCount

          emissionCounts(qFrom)(sequence(i)) += thisTransitionCount

        }
      }
     
    }


    PartialCounts(
      totalProb,
      HashMap(
        stateCounts.keySet.map{ q =>
          q -> stateCounts(q)
        }.toSeq:_*
      ),
      HashMap(
        transitionCounts.keySet.map{ from =>
          from -> HashMap(
            transitionCounts(from).keySet.map{ to =>
              to -> transitionCounts(from)(to)
            }.toSeq:_*
          )
        }.toSeq:_*
      ),
      HashMap(
        emissionCounts.keySet.map{ q =>
          q -> HashMap(
            emissionCounts(q).keySet.map{ obs =>
              obs -> emissionCounts(q)(obs)
            }.toSeq:_*
          )
        }.toSeq:_*
      )
    )
  }

  def reestimate( sequence:List[Observation] ) = {
    val PartialCounts(
      totalProb,
      stateCounts,
      transitionCounts,
      emissionCounts
    ) = computePartialCounts( sequence ) 

          // println( "Partial Counts Are " )

          // println( "State counts" )

          // println(
          //   stateCounts.keySet.map{ q => q + ": " + stateCounts(q) }.mkString("\n\t","\n\t","\n")
          // )
          // 
          // println( "Transition counts" )

          // println(
          //   transitionCounts.keySet.map{ qFrom =>
          //     transitionCounts(qFrom).keySet.map{ qTo =>
          //       qFrom + " -> " + qTo + ": " + transitionCounts(qFrom)(qTo)
          //     }.mkString("","\n\t","\n")
          //   }.mkString("\n\t","\n\t","\n")
          // )

          // println( "Emission counts" )

          // println(
          //   emissionCounts.keySet.map{ q =>
          //     emissionCounts(q).keySet.map{ obs =>
          //       q  + " -> " + obs + ": " + emissionCounts(q)(obs)
          //     }.mkString("","\n","\n")
          //   }.mkString("\n\t","\n\t","\n")
          // )

    val stateCountsTotal = stateCounts.values.sum
    val stateProbs = HashMap(
      stateCounts.keySet.map{ q =>
        q -> ( stateCounts( q ) / stateCountsTotal )
      }.toSeq:_*
    )

    val transitionProbs = HashMap(
      transitionCounts.keySet.map{ qFrom =>
        val qFromTotal = transitionCounts(qFrom).values.sum
        qFrom -> HashMap(
          transitionCounts(qFrom).keySet.map{ qTo =>
            qTo -> ( transitionCounts( qFrom )( qTo ) / qFromTotal )
          }.toSeq:_*
        )
      }.toSeq:_*
    )

    val emissionProbs = HashMap(
      emissionCounts.keySet.map{ q =>
        val qTotal = emissionCounts(q).values.sum
        q -> HashMap(
          emissionCounts(q).keySet.map{ obs =>
            obs -> ( emissionCounts( q )( obs ) / qTotal )
          }.toSeq:_*
        )
      }.toSeq:_*
    )

    setEmissionMatrix( emissionProbs )
    setTransitionMatrix( transitionProbs )
    setInitialProbs( stateProbs )

    totalProb
    //easyPeasyTotalProbability( sequence )
  }


  def viterbi( s: List[Observation] ) = {
    def argmax( h:HashMap[HiddenState,Double] ):HiddenState =
      h.keySet.reduceLeft{ (p, q) => if( h(p) > h(q) ) p else q }

    def viterbi_aux(
      backtrace:List[HashMap[HiddenState,HiddenState]], // psi
      previousState:HashMap[HiddenState,Double], // delta
      remaining:List[Observation]
    ):List[HashMap[HiddenState,HiddenState]] = {
      if( remaining == Nil )
        backtrace :+
         HashMap(
           argmax( previousState ) -> argmax( previousState )
         )
      else
        viterbi_aux(
          backtrace :+
          HashMap(
            hiddenStateTypes.map{ to =>
              to -> argmax(
                HashMap(
                  previousState.keySet.map{ from =>
                    from -> previousState(from) * TransitionMatrix(from)(to)
                  }.toSeq:_*
                )
              )
            }.toSeq:_*
          ),
          HashMap(
            hiddenStateTypes.map{ to =>
              to -> (
                previousState.keySet.map{ from =>
                  previousState(from) * TransitionMatrix(from)(to)
                }.max
              ) * EmissionMatrix(to)(remaining.head)
            }.toSeq:_*
          ),
          remaining.tail
        )
    }

    def backtraceReadout( backtrace: List[HashMap[HiddenState,HiddenState]] ) = {
      def backtraceReadout_aux(
        optimalSequence:List[HiddenState],
        bestToState:HiddenState,
        bt_remaining:List[HashMap[HiddenState,HiddenState]]
      ):List[HiddenState] =
        if( bt_remaining == Nil )
          bestToState::optimalSequence
        else
          backtraceReadout_aux(
            bestToState::optimalSequence,
            bt_remaining.last(bestToState),
            bt_remaining.init
          )

        assert( backtrace.last.keySet.toList.size == 1 )
        backtraceReadout_aux(
          Nil,
          backtrace.last.keySet.toList(0),
          backtrace.init
        )
    }

    backtraceReadout(
      viterbi_aux(
        Nil,
        HashMap(
          hiddenStateTypes.map{ q =>
            q -> InitialStateProbabilities(q) * EmissionMatrix(q)(s.head)
          }.toSeq:_*
        ),
        s.tail
      )
    )
  }

  def easyPeasyTotalProbability( s:List[Observation] ) = {
    buildHMM( s )
    val inferencer = new JunctionTreeInferencer()
    inferencer.computeMarginals( hmm )

    def logSpaceMultiplication( logSpaceArray:Array[Double], factor:Factor ) =
      ( logSpaceArray zip factor.asTable().toLogValueArray ).map{ case (a, b) =>
        a + b
      }
    inferencer.lookupJunctionTree().clusterPotentialsArray().foldLeft(Array.fill(hiddenStateTypes.size)(0D))( logSpaceMultiplication
    ).map{ math.exp(_) }.sum
  }

      // I think this is wrong. Use the other total probability function.
      // def totalProbability( allObservations:List[Observation] ):Double = {

      //       /*
      //       def forwardPass_aux(
      //         computed:List[HashMap[HiddenState,Double]],
      //         remaining: List[Observation]
      //       ):List[HashMap[HiddenState,Double]] =
      //         if( remaining == Nil )
      //           computed
      //         else
      //           forwardPass_aux(
      //             computed ++
      //             List(
      //               HashMap(
      //                 hiddenStateTypes.map{ to =>
      //                   to -> (
      //                     computed.last.keySet.map{ from =>
      //                       computed.last( from ) *
      //                       TransitionMatrix( from )( to )
      //                     }.sum *
      //                     EmissionMatrix( to )( remaining.head )
      //                   )
      //                 }.toSeq:_*
      //               )
      //             ),
      //             remaining.tail
      //           )
      //       */

      //   def forwardPass( allObservations:List[Observation] ) = {
      //     var lastAlphas =
      //       HashMap(
      //         hiddenStateTypes.map{ q =>
      //            q -> InitialStateProbabilities( q ) * EmissionMatrix(q)(allObservations(0))
      //         }.toSeq:_*
      //       )

      //     (allObservations.tail).foreach{ obs =>
      //       // println( "---===---===:    " + obs )
      //       lastAlphas = HashMap(
      //         hiddenStateTypes.map{ qTo =>
      //           qTo -> {
      //             hiddenStateTypes.map{ qFrom =>
      //               TransitionMatrix(qFrom)(qTo) * lastAlphas(qFrom)
      //             }.sum
      //           } *
      //           EmissionMatrix(qTo)(obs)
      //         }.toSeq:_*
      //       )
      //     }
      //     lastAlphas
      //   }

      //   forwardPass( allObservations ).values.sum
      // }


  def seeMarginals() {
    val inferencer = new JunctionTreeInferencer()
    inferencer.computeMarginals( hmm )

    hiddenVariables foreach ( someHiddenVar =>
      println( inferencer.lookupMarginal( someHiddenVar ).dumpToString() )
    )
  }

  override def toString =
    "  == HMM Parameters == \n" +
    "InitialProbabilities" +
    InitialStateProbabilities +
    "Transitions:" +
    TransitionMatrix +
    "Emissions" +
    EmissionMatrix
}

