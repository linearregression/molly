package edu.berkeley.cs.boom.molly.derivations

import org.sat4j.minisat.SolverFactory
import edu.berkeley.cs.boom.molly.FailureSpec
import scala.collection.mutable
import org.sat4j.specs.IVecInt
import org.sat4j.core.VecInt
import org.sat4j.tools.ModelIterator
import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions
import nl.grons.metrics.scala.MetricBuilder

import optimus.optimization._
import optimus.algebra._

import pprint.Config.Defaults._

import sext._

object ILPSolver extends Solver {

  protected def solve(failureSpec: FailureSpec, goal: GoalNode,
                    firstMessageSendTimes: Map[String, Int], seed: Set[SolverVariable])
                   (implicit metrics: MetricBuilder):
  Traversable[Set[SolverVariable]] = {

    implicit val problem = MIProblem(SolverLib.oJalgo)
    //implicit val problem = MIProblem(SolverLib.gurobi)

    var timer = System.currentTimeMillis();

    println(s"Boolean formula has ${goal.booleanFormula.clauses} clauses and ${goal.booleanFormula.vars.size} vars")

    val bf = BooleanFormula(goal.booleanFormula).simplifyAll.flipPolarity
    val f = BooleanFormula(goal.booleanFormula).simplifyAll
    println(s"Simplified formula has ${f.clauses} clauses and ${f.vars.size} vars")
    //logger.warn(s"initial formula \n${goal.booleanFormula}")
    logger.debug(s"${System.currentTimeMillis() - timer} millis -- simplification")
    timer = System.currentTimeMillis();
    val formula = bf.convertToCNFAll
    logger.debug(s"${System.currentTimeMillis() - timer} millis -- CNF")
    println(s"compact formula  for ${goal.tuple}: ${formula.conjuncts}")

    val importantNodes: Set[String] =
      formula.root.vars.filter(_._3 < failureSpec.eot).map(_._1).toSet ++
        seed.collect { case cf: CrashFailure => cf.node }
    if (importantNodes.isEmpty) {
      logger.debug(s"Goal ${goal.tuple} has no important nodes; skipping SAT solver")
      return Set.empty
    } else {
      logger.debug(s"Goal ${goal.tuple} has important nodes $importantNodes")
    }

    // Add constraints to ensure that each node crashes at a single time, or never crashes:
    for (node <- importantNodes) {
      // There's no point in considering crashes before the first time that a node sends a message,
      // since all such scenarios will be equivalent to crashing when sending the first message:
      val firstSendTime = firstMessageSendTimes.getOrElse(node, 1)
      // Create one variable for every time at which the node could crash
      val crashVars = (firstSendTime to failureSpec.eot - 1).map(t => CrashFailure(node, t))
      //val crashVars = (firstSendTime to failureSpec.eff).map(t => CrashFailure(node, t))
      // Include any crashes specified in the seed, since they might be excluded by the
      // "no crashes before the first message was sent" constraint:
      val seedCrashes = seed.collect { case c: CrashFailure => c }
      // An extra variable for scenarios where the node didn't crash:
      val neverCrashed = NeverCrashed(node)
      // Each node crashes at a single time, or never crashes:
      //solver.addExactly((crashVars ++ seedCrashes).toSet ++ Seq(neverCrashed), 1)
    }
    // If there are at most C crashes, then at least (N - C) nodes never crash:

    val stringToMIPVar = mutable.HashMap[String, MPIntVar]()
    val stringToFailure = mutable.HashMap[String, (String, String, Int)]()
    val knownConstraints = mutable.HashSet[Set[MPIntVar]]()

    lazy val fails = formula.root.vars.filter(_._3 < failureSpec.eff)
    fails.foreach { v =>
      val str = v.toString
      val newVar = MPIntVar(str, 0 to 1)
      stringToMIPVar(str) = newVar
      stringToFailure(str) = v
    }
    logger.debug(s"conjuncts: ${formula.conjuncts.conjunctz}")
    for (disjunct <- formula.conjuncts.conjunctz;
         if !disjunct.disjuncts.isEmpty //&& disjunct.disjuncts.forall(d => fails.contains(d))//&& !disjunct.disjuncts.exists(d => d._3 > failureSpec.eff)
    ) {
      val messageLosses = disjunct.disjuncts.map(MessageLoss.tupled)
      val crashes = messageLosses.flatMap { loss =>
        val firstSendTime = firstMessageSendTimes.getOrElse(loss.from, 1)
        val crashTimes = firstSendTime to loss.time
        crashTimes.map(t => CrashFailure(loss.from, t))
      }

      var disjunctVars = disjunct.disjuncts.flatMap(d => stringToMIPVar.get(d.toString))
      if (!knownConstraints.contains(disjunctVars)) {
        logger.debug(s"Add: SUM($disjunctVars) >= 1")
        problem.add(sum(disjunctVars) >= 1)
        knownConstraints.add(disjunctVars)
      }
    }

    // AFAICT, optimus freaks out if your constraints have quadratic terms but your
    // objective function does not.  so, we add a dummy quadratic term 1 * 1 = 1
    val zero = MPIntVar("z", 0 to 0)
    val one = MPIntVar("z", 1 to 1)
    logger.debug(s"MINIMIZE(SUM(${stringToMIPVar.values}}))")
    minimize(sum(stringToMIPVar.values) + zero * zero)

    val models = ArrayBuffer[Set[SolverVariable]]()
    metrics.timer("ilp-time").time {
      while (problem.getStatus != ProblemStatus.INFEASIBLE) { // && problem.getStatus != ProblemStatus.SUBOPTIMAL) {
        problem.start()
        val failures = stringToFailure.keys.filter(k => stringToMIPVar.getOrElse(k, null).value.getOrElse(-1) == 1.0).map(stringToFailure)
        models += failures.map(f => MessageLoss.tupled(f).asInstanceOf[SolverVariable]).toSet
        val allMyChildren = failures.map(f => stringToMIPVar(f.toString())).toSet
        val nonFailures = fails.filter(f => !allMyChildren.contains(stringToMIPVar(f.toString))).map(n => stringToMIPVar(n.toString)).toSet
        val siz = allMyChildren.size - 1
        problem.add(sum(allMyChildren) - sum(nonFailures) <= siz)
      }
    }
    logger.warn(s"MODELS: ${models.size}")
    models
  }
}
