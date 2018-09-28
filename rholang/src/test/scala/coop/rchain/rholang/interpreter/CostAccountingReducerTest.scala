package coop.rchain.rholang.interpreter

import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.models.Channel.ChannelInstance
import coop.rchain.models.Channel.ChannelInstance.Quote
import coop.rchain.models.Expr.ExprInstance.{EVarBody, GString}
import coop.rchain.models.Var.VarInstance.{BoundVar, FreeVar}
import coop.rchain.models._
import coop.rchain.models.rholang.implicits._
import coop.rchain.rholang.interpreter.Reduce.DebruijnInterpreter
import coop.rchain.rholang.interpreter.accounting.{Chargeable, Cost, CostAccount, CostAccountingAlg, _}
import coop.rchain.rholang.interpreter.errors.OutOfPhlogistonsError
import coop.rchain.rholang.interpreter.storage.implicits._
import coop.rchain.rholang.interpreter.storage.{ChargingRSpace, ChargingRSpaceTest, TuplespaceAlg}
import coop.rchain.rspace.internal.{Datum, Row}
import monix.eval.{Coeval, Task}
import monix.execution.Scheduler.Implicits.global
import org.scalactic.TripleEqualsSupport
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

class CostAccountingReducerTest extends FlatSpec with Matchers with TripleEqualsSupport {

  behavior of "Cost accounting in Reducer"

  it should "charge for the successful substitution" in {
    val term: Expr => Par = expr => Par(bundles = Seq(Bundle(Par(exprs = Seq(expr)))))
    val varTerm           = term(Expr(EVarBody(EVar(Var(BoundVar(0))))))
    val substTerm         = term(Expr(GString("1")))
    val env               = Env.makeEnv[Par](Expr(GString("1")))
    val termCost          = Chargeable[Par].cost(substTerm)
    val initCost          = CostAccount(1000)
    val costAlg           = CostAccountingAlg.unsafe[Coeval](initCost)
    val res               = Reduce.substituteAndCharge[Par, Coeval](varTerm, 0, env, costAlg).attempt.value
    assert(res === Right(substTerm))
    assert(costAlg.get().value.cost === (initCost.cost - Cost(termCost)))
  }

  it should "charge for failed substitution" in {
    val term: Expr => Par = expr => Par(bundles = Seq(Bundle(Par(exprs = Seq(expr)))))
    val varTerm           = term(Expr(EVarBody(EVar(Var(FreeVar(0))))))
    val env               = Env.makeEnv[Par](Expr(GString("1")))
    val originalTermCost  = Chargeable[Par].cost(varTerm)
    val initCost          = CostAccount(1000)
    val costAlg           = CostAccountingAlg.unsafe[Coeval](initCost)
    val res               = Reduce.substituteAndCharge[Par, Coeval](varTerm, 0, env, costAlg).attempt.value
    assert(res.isLeft)
    assert(costAlg.get().value.cost === (initCost.cost - Cost(originalTermCost)))
  }

  it should "stop if OutOfPhloError is returned from RSpace" in {
    val tuplespaceAlg = new TuplespaceAlg[Task] {
      override def produce(
          chan: Channel,
          data: ListChannelWithRandom,
          persistent: Boolean
      ): Task[Unit] =
        Task.raiseError(OutOfPhlogistonsError)
      override def consume(
          binds: Seq[(BindPattern, ChannelInstance.Quote)],
          body: ParWithRandom,
          persistent: Boolean
      ): Task[Unit] = Task.raiseError(OutOfPhlogistonsError)
    }

    implicit val errorLog = new ErrorLog()
    implicit val rand     = Blake2b512Random(128)
    implicit val costAlg  = CostAccountingAlg.unsafe[Task](CostAccount(1000))
    val reducer           = new DebruijnInterpreter[Task, Task.Par](tuplespaceAlg, Map.empty)
    val send              = Send(Channel(Quote(GString("x"))), Seq(Par()))
    val test              = reducer.inj(send).attempt.runSyncUnsafe(1.second)
    assert(test === Left(OutOfPhlogistonsError))
  }

  it should "stop interpreter threads as soon as deploy runs out of phlo" in {
    // Given
    // new x in { @x!("a") | @x!("b") }
    // and not enough phlos to reduce successfully
    // only one of the branches we should be persisted in the tuplespace

    val channel = Quote(GPrivateBuilder("x"))

    val a: Par = GString("a")
    val b: Par = GString("b")
    val program =
      Par(sends = Seq(Send(channel, Seq(a)), Send(channel, Seq(b))))

    implicit val rand   = Blake2b512Random(Array.empty[Byte])
    implicit val errLog = new ErrorLog()
    lazy val pureRSpace = ChargingRSpaceTest.createRhoISpace()
    lazy val (_, reducer, _) =
      RholangAndScalaDispatcher.create[Task, Task.Par](pureRSpace, Map.empty, Map.empty)

    def plainSendCost(p: Par): Cost = {
      val storageCost = ChargingRSpace.storageCostProduce(
        channel,
        ListChannelWithRandom(Seq(Channel(Quote(p))))
      )
      val substitutionCost = Cost(Chargeable[Channel].cost(channel)) + Cost(
        Chargeable[Par].cost(p)
      )
      CHANNEL_EVAL_COST + substitutionCost + storageCost + SEND_EVAL_COST
    }
    val sendACost = plainSendCost(a)
    val sendBCost = plainSendCost(b)

    val initPhlos = sendACost + sendBCost - SEND_EVAL_COST - Cost(1)
    reducer.setAvailablePhlos(initPhlos).runSyncUnsafe(1.second)

    val test   = reducer.inj(program)
    val result = test.attempt.runSyncUnsafe(5.seconds)
    val errors = errLog.readAndClearErrorVector()
    assert(errors === Vector.empty)
    assert(result === Left(OutOfPhlogistonsError))

    def data(p: Par, rand: Blake2b512Random) = Row(
      List(
        Datum.create(
          Channel(channel),
          ListChannelWithRandom(Seq(Channel(Quote(p))), rand),
          false
        )
      ),
      List()
    )

    def stored(p: Par, rand: Blake2b512Random): Boolean =
      pureRSpace.store.toMap.get(List(channel)) === Some(data(p, rand))

    assert(stored(a, rand.splitByte(0)) || stored(b, rand.splitByte(1)))
  }
}
