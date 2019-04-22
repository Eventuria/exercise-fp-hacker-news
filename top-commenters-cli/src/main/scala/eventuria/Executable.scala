package eventuria

import org.http4s.Uri
import cats.effect.IO
import cats.effect._
import org.http4s.client.blaze._
import org.http4s.client._
import scala.concurrent.ExecutionContext.Implicits.global
import fs2._
import _root_.io.circe.Decoder



object HackerNewsDomain {
  type StoryId     = Int
  type CommentId   = Int
  type Commenter   = String

  sealed trait Story
  case class StoryCommented   (id: StoryId  , title:String, commentIds:List[CommentId])             extends Story
  case class StoryNotCommented(id: StoryId  , title:String)                                         extends Story

  sealed trait Comment
  case class AnonymousComment (id: CommentId, commentIds:List[CommentId])                             extends Comment
  case class CommentWithCommenter (id: CommentId, commenter : Commenter , commentIds:List[CommentId]) extends Comment
}

object JsonDecoding {
  import HackerNewsDomain._

  implicit val storyDecoder : Decoder[Story] = Decoder.instance (json =>
    for {
      id <- json.downField("id").as[StoryId]
      title <- json.downField("title").as[String]
      commentIdsOption <- json.downField("kids").as[Option[List[CommentId]]]
    }
      yield { commentIdsOption match {
        case None => StoryNotCommented (id,title)
        case Some (commentIds) => StoryCommented (id,title,commentIds)
      }
      })
}

object TopCommenterApp extends IOApp {

  import HackerNewsDomain._

  class HackerNewsClient(httpClient : Client[IO]) {

    val baseUrl = Uri.uri ("https://hacker-news.firebaseio.com")

    def streamTopStories(max : Int) : Stream[IO,Either[String,Story]] = {
      import org.http4s.circe.CirceEntityDecoder._
      import JsonDecoding._
      Stream.eval(
        httpClient
          .expect[List[Int]](baseUrl / "v0" / "topstories.json")
          .map(_.take(max)))
        .flatMap(Stream.emits(_))
          .mapAsync(max)(storyId =>
            httpClient
              .expect[Story](baseUrl / "v0" / "item" / s"$storyId.json")
              .map(result => Right(result).asInstanceOf[Either[String,Story]]))
          .handleErrorWith { e => Stream.emit(Left(e.toString))  }
      }


  }

  object HackerNewsClient {
    def apply(httpClient: Client[IO]): HackerNewsClient = new HackerNewsClient(httpClient)
  }

  def run(args: List[String]): IO[ExitCode] = BlazeClientBuilder[IO](global).resource.use( httpClient =>
     for {
      _         <- IO (println (s"Starting"))
      _         <- HackerNewsClient(httpClient)
                      .streamTopStories (30)
                      .evalTap[IO](story => IO (println (s"$story")))
                      .compile.drain

     } yield ExitCode.Success
  )
}