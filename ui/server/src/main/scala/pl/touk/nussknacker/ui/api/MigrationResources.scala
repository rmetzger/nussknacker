package pl.touk.nussknacker.ui.api

import akka.http.scaladsl.server.{Directives, Route}
import argonaut.EncodeJson
import pl.touk.nussknacker.ui.EspError
import pl.touk.nussknacker.ui.process.displayedgraph.DisplayableProcess
import pl.touk.nussknacker.ui.process.migrate.ProcessMigrator
import pl.touk.nussknacker.ui.process.repository.ProcessRepository
import pl.touk.nussknacker.ui.process.repository.ProcessRepository.ProcessNotFoundError
import pl.touk.nussknacker.ui.security.{LoggedUser, Permission}
import pl.touk.nussknacker.ui.util.ProcessComparator
import pl.touk.http.argonaut.Argonaut62Support
import scala.concurrent.{ExecutionContext, Future}
import ProcessComparator._

class MigrationResources(migrator: ProcessMigrator, processRepository: ProcessRepository)(implicit ec: ExecutionContext)
  extends Directives with Argonaut62Support with RouteWithUser {


  def route(implicit user: LoggedUser) : Route = {
    authorize(user.hasPermission(Permission.Deploy)) {

      pathPrefix("migration") {
          path("compare" / Segment) { processId =>
            get {
              complete {
                implicit val codec = ProcessComparator.codec
                withProcess(processId, (process) => migrator.compare(process))
              }
            }
          } ~
          path("migrate" / Segment) { processId =>
            post {
              complete {
                withProcess(processId, (process) => migrator.migrate(process))
              }
            }
          }
      }
    }
  }

  private def withProcess[T:EncodeJson](processId: String, fun: (DisplayableProcess) => Future[Either[EspError, T]])(implicit user: LoggedUser) = {
    processRepository.fetchLatestProcessDetailsForProcessId(processId).map {
      _.flatMap(_.json)
    }.flatMap {
      case Some(dispProcess) => fun(dispProcess)
      case None => Future.successful(Left(ProcessNotFoundError(processId)))
    }.map(EspErrorToHttp.toResponseEither[T])
  }


}