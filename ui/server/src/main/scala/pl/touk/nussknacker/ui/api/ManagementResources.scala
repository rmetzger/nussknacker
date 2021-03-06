package pl.touk.nussknacker.ui.api

import akka.actor.ActorRef
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.Timeout
import akka.http.scaladsl.server.{Directive, RequestContext, Route, RouteResult}
import akka.http.scaladsl.server.Directives._
import argonaut.Argonaut._
import argonaut.PrettyParams
import com.typesafe.scalalogging.LazyLogging
import pl.touk.nussknacker.engine.api.deployment.test.{TestData, TestResults}
import pl.touk.nussknacker.engine.canonicalgraph.CanonicalProcess
import pl.touk.nussknacker.engine.definition.DefinitionExtractor.PlainClazzDefinition
import pl.touk.nussknacker.ui.api.ProcessesResources.UnmarshallError
import pl.touk.nussknacker.ui.codec.UiCodecs
import pl.touk.nussknacker.ui.process.deployment.{Cancel, Deploy, Snapshot, Test}
import pl.touk.nussknacker.ui.process.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.ui.process.marshall.{ProcessConverter, UiProcessMarshaller}
import pl.touk.nussknacker.ui.processreport.{NodeCount, ProcessCounter, RawCount}
import pl.touk.nussknacker.ui.security.{LoggedUser, Permission}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._


class ManagementResources(typesInformation: List[PlainClazzDefinition], processCounter: ProcessCounter,
                          val managementActor: ActorRef)(implicit ec: ExecutionContext, mat: Materializer) extends Directives with LazyLogging  with RouteWithUser {

  import pl.touk.nussknacker.ui.codec.UiCodecs.displayableProcessCodec
  val codecs = UiCodecs.ContextCodecs(typesInformation)
  val processMarshaller = UiProcessMarshaller()

  import codecs._

  implicit val timeout = Timeout(1 minute)

  def route(implicit user: LoggedUser): Route = {
    authorize(user.hasPermission(Permission.Deploy)) {
        path("adminProcessManagement" / "snapshot" / Segment / Segment) { (processId, savepointDir) =>
          post {
            complete {
              (managementActor ? Snapshot(processId, user, savepointDir))
                .mapTo[String].map(path => HttpResponse(entity = path, status = StatusCodes.OK))
                .recover(EspErrorToHttp.errorToHttp)
            }
          }
        } ~
        path("adminProcessManagement" / "deploy" / Segment / Segment) { (processId, savepointPath) =>
         post {
              complete {
                (managementActor ? Deploy(processId, user, Some(savepointPath)))
                  .map { _ => HttpResponse(status = StatusCodes.OK) }
                  .recover(EspErrorToHttp.errorToHttp)
              }
          }
        } ~
        path("processManagement" / "deploy" / Segment) { processId =>
          post {
            complete {
              (managementActor ? Deploy(processId, user, None))
                .map { _ => HttpResponse(status = StatusCodes.OK) }
                .recover(EspErrorToHttp.errorToHttp)
            }
          }
        } ~
        path("processManagement" / "cancel" / Segment) { processId =>
          post {
            complete {
              (managementActor ? Cancel(processId, user))
                .map { _ => HttpResponse(status = StatusCodes.OK) }
                .recover(EspErrorToHttp.errorToHttp)
            }
          }
        } ~
        path("processManagement" / "test" / Segment) { processId =>
          post {
            //There is bug in akka-http in formFields, so we use custom toStrict method
            //issue: https://github.com/akka/akka/issues/19506
            //workaround: https://gist.github.com/rklaehn/d4d3ee43443b0f4741fb#file-uploadhandlertostrict-scala
            toStrict(5.second) {
              formFields('testData.as[Array[Byte]], 'processJson) { (testData, displayableProcessJson) =>
                complete {
                  performTest(processId, testData, displayableProcessJson).map { results =>
                    HttpResponse(status = StatusCodes.OK, entity =
                      HttpEntity(ContentTypes.`application/json`, results.asJson.pretty(PrettyParams.spaces2)))
                  }.recover(EspErrorToHttp.errorToHttp)
                }
              }
            }
          }
        }
    }
  }

  private def performTest(processId: String, testData: Array[Byte], displayableProcessJson: String)(implicit user: LoggedUser): Future[ResultsWithCounts] = {
    displayableProcessJson.decodeEither[DisplayableProcess] match {
      case Right(process) =>
        val canonical = ProcessConverter.fromDisplayable(process)
        val canonicalJson = processMarshaller.toJson(canonical, PrettyParams.nospace)
        (managementActor ? Test(processId, canonicalJson, TestData(testData), user)).mapTo[TestResults].map { results =>
          ResultsWithCounts(results, computeCounts(canonical, results))
        }
      case Left(error) =>
        Future.failed(UnmarshallError(error))
    }
  }

  private def computeCounts(canonical: CanonicalProcess, results: TestResults) : Map[String, NodeCount] = {
    val counts = results.nodeResults.map { case (key, nresults) =>
      key -> RawCount(nresults.size.toLong, results.exceptions.find(_.nodeId.contains(key)).size.toLong)
    }
    processCounter.computeCounts(canonical, counts.get)
  }

  private def toStrict(timeout: FiniteDuration): Directive[Unit] = {
    def toStrict0(inner: Unit ⇒ Route): Route = {
      val result: RequestContext ⇒ Future[RouteResult] = c ⇒ {
        // call entity.toStrict (returns a future)
        c.request.entity.toStrict(timeout).flatMap { strict ⇒
          // modify the context with the strictified entity
          val c1 = c.withRequest(c.request.withEntity(strict))
          // call the inner route with the modified context
          inner()(c1)
        }
      }
      result
    }
    Directive[Unit](toStrict0)
  }

}

case class ResultsWithCounts(results: TestResults, counts: Map[String, NodeCount])
