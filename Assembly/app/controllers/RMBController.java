package controllers;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import net.nationstatesplusplus.assembly.model.websocket.DataRequest;
import net.nationstatesplusplus.assembly.model.websocket.PageType;
import net.nationstatesplusplus.assembly.model.websocket.RequestType;
import net.nationstatesplusplus.assembly.util.DatabaseAccess;
import net.nationstatesplusplus.assembly.util.Utils;

import org.apache.commons.dbutils.DbUtils;
import org.apache.commons.lang.builder.ToStringBuilder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import org.joda.time.Duration;
import org.spout.cereal.config.yaml.YamlConfiguration;

import play.libs.Akka;
import play.libs.F.Function;
import play.libs.F.Promise;
import play.libs.Json;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Result;
import play.mvc.Results;

import com.limewoodMedia.nsapi.NationStates;

public class RMBController extends NationStatesController {
	public RMBController(DatabaseAccess access, YamlConfiguration config, NationStates api) {
		super(access, config, api);
	}

	public Result ratePost(int rmbPost, int rating) throws SQLException {
		Result ret = Utils.validateRequest(request(), response(), getAPI(), getDatabase());
		if (ret != null) {
			return ret;
		}
		if (rmbPost < 12 || rating > 1) {
			return Results.badRequest();
		}
		final String nation = Utils.sanitizeName(Utils.getPostValue(request(), "nation"));
		final int nationId = getDatabase().getNationId(nation);
		if (nationId == -1) {
			return Results.badRequest();
		}
		try (Connection conn = getConnection()) {
			JsonNode ratings = rateRMBPost(getDatabase(), conn, nation, nationId, rmbPost, rating);
			Map<String, Object> data = new HashMap<String, Object>();
			data.put("rmb_post_id", rmbPost);
			getDatabase().getWebsocketManager().onUpdate(PageType.REGION, RequestType.RMB_RATINGS, new DataRequest(RequestType.RMB_RATINGS, data), ratings);
		} 
		Utils.handleDefaultPostHeaders(request(), response());
		return Results.ok();
	}

	public static JsonNode rateRMBPost(DatabaseAccess access, Connection conn, String nation, int nationId, int rmbPost, int rating) throws SQLException {
		if (rating < 0) {
			PreparedStatement delete = conn.prepareStatement("DELETE FROM assembly.rmb_ratings WHERE nation = ? AND rmb_post = ?");
			delete.setInt(1, nationId);
			delete.setInt(2, rmbPost);
			delete.executeUpdate();
			DbUtils.closeQuietly(delete);
		} else {
			PreparedStatement update = conn.prepareStatement("UPDATE assembly.rmb_ratings SET rating_type = ? WHERE nation = ? AND rmb_post = ?");
			update.setInt(1, rating);
			update.setInt(2, nationId);
			update.setInt(3, rmbPost);
			if (update.executeUpdate() == 0) {
				PreparedStatement insert = conn.prepareStatement("INSERT INTO assembly.rmb_ratings (nation, nation_name, rating_type, rmb_post) VALUES (?, ?, ?, ?)");
				insert.setInt(1, nationId);
				insert.setString(2, nation);
				insert.setInt(3, rating);
				insert.setInt(4, rmbPost);
				insert.executeUpdate();
				DbUtils.closeQuietly(insert);
			}
			DbUtils.closeQuietly(update);
		}

		PreparedStatement update = conn.prepareStatement("UPDATE assembly.region SET rmb_cache = rmb_cache + 1 WHERE id = (SELECT region FROM assembly.nation WHERE nation.id = ?)");
		update.setInt(1, nationId);
		update.executeUpdate();
		DbUtils.closeQuietly(update);

		return calculateTotalPostRatings(access, conn, rmbPost);
	}

	private static Function<JsonNode, Promise<Result>> getAsyncResult(final Request request, final Response response, final String cacheLen) {
		return new Function<JsonNode, Promise<Result>>() {
			@Override
			public Promise<Result> apply(final JsonNode node) throws Throwable {
				return Promise.wrap(akka.dispatch.Futures.future((new Callable<Result>() {
					@Override
					public Result call() throws Exception {
						Result result = Utils.handleDefaultGetHeaders(request, response, String.valueOf(node.hashCode()), cacheLen);
						if (result != null) {
							return result;
						}
						return Results.ok(node).as("application/json");
					}
					
				}), Akka.system().dispatcher()));
			}
		};
	}

	public Promise<Result> getPostRatings(final int rmbPost, final int rmbCache) throws SQLException {
		Promise<JsonNode> promise = Promise.wrap(akka.dispatch.Futures.future((new Callable<JsonNode>() {
			@Override
			public JsonNode call() throws Exception {
				Connection conn = null;
				try {
					conn = getConnection();
					JsonNode ratings = calculatePostRatings(conn, rmbPost);
					return ratings;
				} finally {
					DbUtils.closeQuietly(conn);
				}
			}
		}), Akka.system().dispatcher()));
		Promise<Result> result = promise.flatMap(getAsyncResult(request(), response(), rmbCache == -1 ? "10" : "86400"));
		return result;
	}

	@Deprecated
	public static JsonNode calculatePostRatings(Connection conn, int rmbPost) throws SQLException {
		List<Map<String, String>> list = new ArrayList<Map<String, String>>();
		PreparedStatement select = conn.prepareStatement("SELECT nation_name, rating_type FROM assembly.rmb_ratings WHERE rmb_post = ?");
		select.setInt(1, rmbPost);
		ResultSet result = select.executeQuery();
		while(result.next()) {
			Map<String, String> ratings = new HashMap<String, String>(2);
			ratings.put("nation", result.getString(1));
			ratings.put("type", String.valueOf(result.getInt(2)));
			ratings.put("rmb_post", String.valueOf(rmbPost));
			list.add(ratings);
		}
		DbUtils.closeQuietly(result);
		DbUtils.closeQuietly(select);
		return Json.toJson(list);
	}

	public static JsonNode calculateTotalPostRatings(DatabaseAccess access, Connection conn, int rmbPost) throws SQLException {
		List<Map<String, String>> list = new ArrayList<Map<String, String>>();
		PreparedStatement select = conn.prepareStatement("SELECT nation_name, rating_type FROM assembly.rmb_ratings WHERE rmb_post = ?");
		select.setInt(1, rmbPost);
		ResultSet result = select.executeQuery();
		while(result.next()) {
			Map<String, String> ratings = new HashMap<String, String>(2);
			ratings.put("nation", access.getNationTitle(result.getString(1)));
			ratings.put("type", String.valueOf(result.getInt(2)));
			list.add(ratings);
		}
		DbUtils.closeQuietly(result);
		DbUtils.closeQuietly(select);
		
		Map<String, Object> postRatings = new HashMap<String, Object>();
		postRatings.put("rmb_post", rmbPost);
		postRatings.put("ratings", list);
		return Json.toJson(postRatings);
	}

	public Result getRMBCache(String region) throws SQLException {
		Connection conn = getConnection();
		Map<String, Integer> cache = new HashMap<String, Integer>(2);
		try {
			PreparedStatement select = conn.prepareStatement("SELECT rmb_cache FROM assembly.region WHERE name = ?");
			select.setString(1, region);
			ResultSet result = select.executeQuery();
			while(result.next()) {
				cache.put("rmb_cache", result.getInt(1));
			}
			DbUtils.closeQuietly(result);
			DbUtils.closeQuietly(select);
		} finally {
			DbUtils.closeQuietly(conn);
		}
		Result result = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(cache.hashCode()), "10");
		if (result != null) {
			return result;
		}
		return Results.ok(Json.toJson(cache)).as("application/json");
	}

	public Result hasComments(int rmbPost) throws SQLException {
		Connection conn = getConnection();
		Map<String, Integer> comments = new HashMap<String, Integer>(1);
		try {
			PreparedStatement select = conn.prepareStatement("SELECT count(id) FROM assembly.rmb_comments WHERE rmb_message_id = ?");
			select.setInt(1, rmbPost);
			ResultSet result = select.executeQuery();
			if(result.next()) {
				comments.put("comments", result.getInt(1));
			} else {
				comments.put("comments", 0);
			}
			DbUtils.closeQuietly(result);
			DbUtils.closeQuietly(select);
		} finally {
			DbUtils.closeQuietly(conn);
		}
		Result result = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(comments.hashCode()));
		if (result != null) {
			return result;
		}
		return Results.ok(Json.toJson(comments)).as("application/json");
	}

	public Result getComments(int rmbPost) throws SQLException, ExecutionException {
		Connection conn = getConnection();
		List<RMBComment> list = new ArrayList<RMBComment>();
		try {
			PreparedStatement select = conn.prepareStatement("SELECT c.id, c.timestamp, c.comment, c.nation_id, sum(a.like) as likes, sum(a.flag) as flags FROM assembly.rmb_comments AS c LEFT OUTER JOIN assembly.rmb_comment_actions AS a ON a.rmb_comment_id = c.id WHERE c.rmb_message_id = ? GROUP BY c.id ORDER BY c.timestamp ASC");
			select.setInt(1, rmbPost);
			ResultSet result = select.executeQuery();
			while(result.next()) {
				list.add(new RMBComment(result.getInt(1), result.getLong(2), result.getString(3), getDatabase().getReverseIdCache().get(result.getInt(4)), result.getInt(5), result.getInt(6)));
			}
			DbUtils.closeQuietly(result);
			DbUtils.closeQuietly(select);
		} finally {
			DbUtils.closeQuietly(conn);
		}
		Result result = Utils.handleDefaultGetHeaders(request(), response(), String.valueOf(list.hashCode()));
		if (result != null) {
			return result;
		}
		return Results.ok(Json.toJson(list)).as("application/json");
	}

	public static class RMBComment {
		RMBComment(int id, long timestamp, String comment, String nation, int likes, int flags) {
			this.id = id;
			this.timestamp = timestamp;
			this.comment = comment;
			this.nation = nation;
			this.likes = likes;
			this.flags = flags;
		}

		@JsonProperty
		int id;
		@JsonProperty
		long timestamp;
		@JsonProperty
		String comment;
		@JsonProperty
		String nation;
		@JsonProperty
		int likes;
		@JsonProperty
		int flags;

		@Override
		public int hashCode() {
			return id;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof RMBComment) {
				return ((RMBComment)obj).id == id;
			}
			return false;
		}

		@Override
		public String toString() {
			return ToStringBuilder.reflectionToString(this);
		}
	}

	private int getRMBCommentNation(Connection conn, int commentId) throws SQLException {
		PreparedStatement select = null;
		ResultSet result = null;
		try {
			select = conn.prepareStatement("SELECT nation_id FROM assembly.rmb_comments WHERE id = ?");
			select.setInt(1, commentId);
			result = select.executeQuery();
			if (result.next()) {
				return result.getInt(1);
			}
			return -1;
		} finally {
			DbUtils.closeQuietly(result);
			DbUtils.closeQuietly(select);
		}
	}

	public Result flagComment(int commentId, boolean flag) throws SQLException {
		Result invalid = Utils.validateRequest(request(), response(), getAPI(), getDatabase());
		if (invalid != null) {
			return invalid;
		}
		String nation = Utils.getPostValue(request(), "nation");
		final int nationId = getDatabase().getNationId(nation);
		Connection conn = getConnection();
		if (getRMBCommentNation(conn, commentId) == nationId) {
			Utils.handleDefaultGetHeaders(request(), response(), null, "0");
			return Results.badRequest();
		}
		try {
			PreparedStatement select = conn.prepareStatement("SELECT id, like FROM assembly.rmb_comment_actions WHERE rmb_message_id = ? AND nation_id = ?");
			select.setInt(1, commentId);
			select.setInt(2, nationId);
			ResultSet result = select.executeQuery();
			if (result.next()) {
				int id = result.getInt(1);
				byte liked = result.getByte(2);
				PreparedStatement update = conn.prepareStatement("UPDATE assembly.rmb_comment_actions SET flag = ?, like = ? WHERE id = ?");
				update.setByte(1, (byte) (flag ? 1 : 0));
				update.setByte(2, (byte) (flag ? 0 : liked));
				update.setInt(3, id);
				update.executeUpdate();
				DbUtils.closeQuietly(update);
			} else {
				PreparedStatement update = conn.prepareStatement("INSERT INTO assembly.rmb_comment_actions (rmb_comment_id, nation_id, flag, like) VALUES (?, ?, ?, )");
				update.setInt(1, commentId);
				update.setInt(2, nationId);
				update.setByte(3, (byte) 1);
				update.setByte(4, (byte) 0);
				update.executeUpdate();
				DbUtils.closeQuietly(update);
			}
			DbUtils.closeQuietly(result);
			DbUtils.closeQuietly(select);
		} finally {
			DbUtils.closeQuietly(conn);
		}
		Utils.handleDefaultGetHeaders(request(), response(), null, "0");
		return Results.ok();
	}

	public Result likeComment(int commentId, boolean like) throws SQLException {
		Result invalid = Utils.validateRequest(request(), response(), getAPI(), getDatabase());
		if (invalid != null) {
			return invalid;
		}
		String nation = Utils.getPostValue(request(), "nation");
		final int nationId = getDatabase().getNationId(nation);
		Connection conn = getConnection();
		if (getRMBCommentNation(conn, commentId) == nationId) {
			Utils.handleDefaultGetHeaders(request(), response(), null, "0");
			return Results.badRequest();
		}
		try {
			PreparedStatement select = conn.prepareStatement("SELECT id, flag FROM assembly.rmb_comment_actions WHERE rmb_message_id = ? AND nation_id = ?");
			select.setInt(1, commentId);
			select.setInt(2, nationId);
			ResultSet result = select.executeQuery();
			if (result.next()) {
				int id = result.getInt(1);
				byte flagged = result.getByte(2);
				PreparedStatement update = conn.prepareStatement("UPDATE assembly.rmb_comment_actions SET flag = ?, like = ? WHERE id = ?");
				update.setByte(1, (byte) (like ? 0 : flagged));
				update.setByte(2, (byte) (like ? 1 : 0));
				update.setInt(3, id);
				update.executeUpdate();
				DbUtils.closeQuietly(update);
			} else {
				PreparedStatement update = conn.prepareStatement("INSERT INTO assembly.rmb_comment_actions (rmb_comment_id, nation_id, flag, like) VALUES (?, ?, ?, )");
				update.setInt(1, commentId);
				update.setInt(2, nationId);
				update.setByte(3, (byte) 0);
				update.setByte(4, (byte) 1);
				update.executeUpdate();
				DbUtils.closeQuietly(update);
			}
			DbUtils.closeQuietly(result);
			DbUtils.closeQuietly(select);
		} finally {
			DbUtils.closeQuietly(conn);
		}
		Utils.handleDefaultGetHeaders(request(), response(), null, "0");
		return Results.ok();
	}

	public Result addComment(int rmbPost) throws SQLException {
		Result invalid = Utils.validateRequest(request(), response(), getAPI(), getDatabase());
		if (invalid != null) {
			return invalid;
		}
		final String comment = Utils.getPostValue(request(), "comment");
		if (comment == null) {
			Utils.handleDefaultGetHeaders(request(), response(), null, "0");
			return Results.badRequest();
		}
		final String nation = Utils.getPostValue(request(), "nation");
		final int nationId = getDatabase().getNationId(nation);
		Connection conn = getConnection();
		try {
			PreparedStatement select = conn.prepareStatement("SELECT timestamp FROM assembly.rmb_comments WHERE rmb_message_id = ? AND nation_id = ? AND timestamp > ?");
			select.setInt(1, rmbPost);
			select.setInt(2, nationId);
			select.setLong(3, System.currentTimeMillis() - Duration.standardSeconds(10).getMillis());
			ResultSet result = select.executeQuery();
			if (result.next()) {
				Utils.handleDefaultGetHeaders(request(), response(), null, "0");
				return Results.status(429);
			}
			DbUtils.closeQuietly(result);
			DbUtils.closeQuietly(select);
			
			PreparedStatement insert = conn.prepareStatement("INSERT INTO assembly.rmb_comments (rmb_message_id, timestamp, comment, nation_id) VALUES (?, ?, ?, ?)");
			insert.setInt(1, rmbPost);
			insert.setLong(2, System.currentTimeMillis());
			insert.setString(3, comment);
			insert.setInt(4, nationId);
			DbUtils.closeQuietly(insert);
		} finally {
			DbUtils.closeQuietly(conn);
		}
		Utils.handleDefaultGetHeaders(request(), response(), null, "0");
		return Results.ok();
	}
}
