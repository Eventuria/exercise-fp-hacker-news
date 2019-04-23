package eventuria

import org.http4s.Uri
import cats.effect.IO
import cats.effect._
import org.http4s.client.blaze._
import org.http4s.client._

import scala.concurrent.ExecutionContext.Implicits.global
import fs2._
import _root_.io.circe.Decoder
import cats.data.NonEmptyList
import cats.Monoid
import cats.implicits._

object HackerNewsDomain {
  type StoryId     = Int
  type CommentId   = Int
  type NbComments   = Int
  type User   = String
  type Comments = NonEmptyList[CommentId]


  sealed trait Story
  case class CommentedStory    (id: StoryId  , title:String, comments: Comments) extends Story
  case class UncommentedStory  (id: StoryId  , title:String) extends Story

  case class TopCommenterStory (
                id: StoryId  ,
                title:String,
                topCommenter : List[(NbComments,Iterable[User])] = List.empty ,
                totalAnonymousComments : NbComments = 0 ,
                totalComments : NbComments = 0 ) {

    override def toString: String = {
      val toStringTopCommenter :String = topCommenter match {
        case topCommenter if topCommenter.isEmpty  => "No Commenter yet\n"
        case topCommenter => Monoid.combineAll[String](
            topCommenter.
              zipWithIndex.
              map {case ((nbComments,users),index) => {
                val userList :String =  Monoid.combineAll[String](users.toList.map(_ ++ " | "))
                s"\n\t${"%3d".format(index+1)}  - $nbComments comment(s) - $userList "}}) ++ "\n"
      }

      s"\n" ++
      s"∙∙∙ Story ∙ $id - $title\n" ++
      s"‣ Total Comments : $totalComments\n (${totalComments +1} api calls to hacker news api" ++
      s"‣ Total Anonymous Comments :$totalAnonymousComments \n" ++
      s"‣ Top Commenters :" ++
       toStringTopCommenter
    }
  }


  case class TopCommenterStoryBuilder (
                 id: StoryId  ,
                 title:String,
                 topCommenter : Map[User,NbComments] = Map.empty ,
                 totalAnonymousComments : NbComments = 0 ,
                 totalComments : NbComments = 0 ) {

    def buildWithFirstXTopCommenter (x : Int):TopCommenterStory = {
      val xBestCommenters = topCommenter.toList.sortBy(- _._2).take(x).toMap
      val xBestCommentersFlippedByNbComments = xBestCommenters.groupBy(_._2).mapValues(_.keys).toList.sortBy(- _._1)
      TopCommenterStory (
        id,
        title,
        topCommenter = xBestCommentersFlippedByNbComments ,
        totalAnonymousComments,
        totalComments)
    }

  }

  sealed trait Comment

  case class IdentifiedComment (id: CommentId, user: User, comments: Comments) extends Comment
  case class UncommentedIdentifiedComment (id: CommentId, user: User)          extends Comment
  case class AnonymousComment (id: CommentId, comments: Comments)              extends Comment
  case class UncommentedAnonymousComment (id: CommentId)                       extends Comment

}

object JsonDecoding {
  import HackerNewsDomain._

  implicit val storyDecoder : Decoder[Story] = Decoder.instance (json =>
      for {
        id <- json.downField("id").as[StoryId]
        title <- json.downField("title").as[String]
        commentIdsOption <- json.downField("kids").as[Option[NonEmptyList[CommentId]]]}
      yield { commentIdsOption match {
          case None  => UncommentedStory (id,title)
          case Some (commentIds) if commentIds.size == 0 => UncommentedStory (id,title)
          case Some (commentIds) if commentIds.size > 0 => CommentedStory (id,title,commentIds)}})

  implicit val commentDecoder : Decoder[Comment] = Decoder.instance (json =>
    for {
      id <- json.downField("id").as[StoryId]
      userOption <- json.downField("by").as[Option[String]]
      commentIdsOption <- json.downField("kids").as[Option[NonEmptyList[CommentId]]]}
    yield { (userOption,commentIdsOption) match {
      case (None, None)  => UncommentedAnonymousComment (id)
      case (None,Some (commentIds)) if commentIds.size == 0 => UncommentedAnonymousComment (id)
      case (None,Some (commentIds)) if commentIds.size > 0 => AnonymousComment (id,commentIds)
      case (Some(user), None)  => UncommentedIdentifiedComment (id,user)
      case (Some(user),Some (commentIds)) if commentIds.size == 0 => UncommentedIdentifiedComment (id,user)
      case (Some(user),Some (commentIds)) if commentIds.size > 0 => IdentifiedComment (id,user,commentIds)}}
  )
}

object TopCommenterApp extends IOApp {

  import HackerNewsDomain._

  class HackerNewsClient(httpClient : Client[IO]) {
    import org.http4s.circe.CirceEntityDecoder._
    import JsonDecoding._
    private val baseUrl = Uri.uri ("https://hacker-news.firebaseio.com")

    def streamTopStories(max : Int) : Stream[IO,Story] = {

      Stream.eval(
        httpClient
          .expect[List[Int]](baseUrl / "v0" / "topstories.json")
          .map(_.take(max)))
        .flatMap(Stream.emits(_))
          .mapAsyncUnordered(max)(storyId =>
            httpClient
              .expect[Story](baseUrl / "v0" / "item" / s"$storyId.json"))
      }


    def streamComments (commentId: CommentId) : Stream [IO, Either[String,Comment]] = {
        Stream
          .emit[IO,CommentId](commentId)
          .evalMap[IO,Either[String,Comment]](commentId =>
            httpClient
              .expect[Comment](baseUrl / "v0" / "item" / s"$commentId.json")
              .map(comment => comment.asRight[String])
              .handleErrorWith { e =>
                for {
                  _ <- IO(println (s"\n Fetching Comment ($commentId) failed (try : ${baseUrl / "v0" / "item" / s"$commentId.json"} ) : ${e}"))
                } yield Left(e.toString)
                })
          .flatMap {
            case Left(e) => Stream.emit[IO,Either[String,Comment]](Left(e))
            case Right(comment @IdentifiedComment(_, _, comments)) =>
              Stream
                .emit[IO,Either[String,Comment]](comment.asRight)
                .merge(Stream.emits[IO,CommentId](comments.toList).flatMap(streamComments))

            // Recursion Base Cases
            case Right(comment @AnonymousComment(_, _))            => Stream.emit[IO,Either[String,Comment]](Right (comment))
            case Right(comment @UncommentedIdentifiedComment(_,_)) => Stream.emit[IO,Either[String,Comment]](Right (comment))
            case Right(comment @UncommentedAnonymousComment(_))    => Stream.emit[IO,Either[String,Comment]](Right (comment))
          }


    }

    def fetchTopCommenterStory(story : Story, xFirstCommenter: Int) : IO[TopCommenterStory] = {
        story match {
          case UncommentedStory(id, title) => IO(TopCommenterStory(id, title))
          case CommentedStory(id, title, comments) =>
            Stream.emits[IO,CommentId](comments.toList)
              .flatMap(commentId => streamComments(commentId))
              .compile
              .fold(TopCommenterStoryBuilder(id, title))( (builder,newCommentFetched) => newCommentFetched match {
                case Left(_) => builder
                case Right(comment) => comment match {
                  case IdentifiedComment(_, user, _) =>
                    TopCommenterStoryBuilder(
                      id,
                      title,
                      topCommenter = builder.topCommenter + (user -> (builder.topCommenter.getOrElse(user,0) + 1)) ,
                      totalAnonymousComments = builder.totalAnonymousComments,
                      totalComments = builder.totalComments + 1)
                  case UncommentedIdentifiedComment(_, user) =>
                    TopCommenterStoryBuilder(
                      id,
                      title,
                      topCommenter = builder.topCommenter + (user -> (builder.topCommenter.getOrElse(user,0) + 1)) ,
                      totalAnonymousComments = builder.totalAnonymousComments,
                      totalComments = builder.totalComments + 1)
                  case AnonymousComment(_,_) =>
                    TopCommenterStoryBuilder(
                      id,
                      title,
                      topCommenter = builder.topCommenter ,
                      totalAnonymousComments = builder.totalAnonymousComments +1,
                      totalComments = builder.totalComments + 1)
                  case UncommentedAnonymousComment(_) =>
                    TopCommenterStoryBuilder(
                      id,
                      title,
                      topCommenter = builder.topCommenter ,
                      totalAnonymousComments = builder.totalAnonymousComments +1,
                      totalComments = builder.totalComments + 1)}})
              .map (_.buildWithFirstXTopCommenter(xFirstCommenter))
        }
    }
  }

  object HackerNewsClient {
    def apply(httpClient: Client[IO]): HackerNewsClient = new HackerNewsClient(httpClient)
  }

  def run(args: List[String]): IO[ExitCode] = BlazeClientBuilder[IO](global).resource.use( httpClient => {
     val xMostRecentTopStories = 30
     for {
      _ <- IO (println (s"Starting"))
      _ <- HackerNewsClient(httpClient)
                .streamTopStories (xMostRecentTopStories)
                .mapAsyncUnordered (xMostRecentTopStories)(story => HackerNewsClient(httpClient).fetchTopCommenterStory(story,10))
                .evalTap[IO](topCommenter => IO (print(topCommenter)))
                .compile
                .drain
     } yield ExitCode.Success}
  )
}