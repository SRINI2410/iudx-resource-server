package iudx.resource.server.authenticator;

import static iudx.resource.server.authenticator.Constants.JSON_CONSUMER;
import static iudx.resource.server.authenticator.Constants.OPEN_ENDPOINTS;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpStatus;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.authentication.TokenCredentials;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.predicate.ResponsePredicate;
import iudx.resource.server.authenticator.authorization.Api;
import iudx.resource.server.authenticator.authorization.AuthorizationContextFactory;
import iudx.resource.server.authenticator.authorization.AuthorizationRequest;
import iudx.resource.server.authenticator.authorization.AuthorizationStrategy;
import iudx.resource.server.authenticator.authorization.JwtAuthorization;
import iudx.resource.server.authenticator.authorization.Method;
import iudx.resource.server.authenticator.model.JwtData;

public class JwtAuthenticationServiceImpl implements AuthenticationService {

  private static final Logger LOGGER = LogManager.getLogger(JwtAuthenticationServiceImpl.class);


  final JWTAuth jwtAuth;
  final WebClient catWebClient;
  final String host;
  final int port;;
  final String path;
  final String audience;
  final String issuer;

  // resourceGroupCache will contains ACL info about all resource group in a resource server
  Cache<String, String> resourceGroupCache = CacheBuilder
      .newBuilder()
      .maximumSize(1000)
      .expireAfterAccess(Constants.CACHE_TIMEOUT_AMOUNT, TimeUnit.MINUTES)
      .build();
  // resourceIdCache will contains info about resources available(& their ACL) in resource server.
  Cache<String, String> resourceIdCache = CacheBuilder
      .newBuilder()
      .maximumSize(1000)
      .expireAfterAccess(Constants.CACHE_TIMEOUT_AMOUNT, TimeUnit.MINUTES)
      .build();

  JwtAuthenticationServiceImpl(Vertx vertx, final JWTAuth jwtAuth, final WebClient webClient, final JsonObject config) {
    this.jwtAuth = jwtAuth;
    this.audience = config.getString("host");
    this.issuer = config.getString("issuer");
    host = config.getString("catServerHost");
    port = config.getInteger("catServerPort");
    path = Constants.CAT_RSG_PATH;

    WebClientOptions options = new WebClientOptions();
    options.setTrustAll(true)
        .setVerifyHost(false)
        .setSsl(true);
    catWebClient = WebClient.create(vertx, options);
  }

  @Override
  public AuthenticationService tokenInterospect(JsonObject request, JsonObject authenticationInfo,
      Handler<AsyncResult<JsonObject>> handler) {

    String endPoint = authenticationInfo.getString("apiEndpoint");
    String id = authenticationInfo.getString("id");
    String token = authenticationInfo.getString("token");

    Future<JwtData> jwtDecodeFuture = decodeJwt(token);
    Future<String> openResourceFuture = isOpenResource(id);
    if (jwtDecodeFuture.succeeded() && jwtDecodeFuture.result().getRole().equals("admin")) {
      return authenticateAdminRole(jwtDecodeFuture.result(), handler);
    }

    ResultContainer result = new ResultContainer();

    jwtDecodeFuture.compose(decodeHandler -> {
      result.jwtData = decodeHandler;
      return isValidAudienceValue(result.jwtData);
    }).compose(audienceHandler -> {
      return openResourceFuture;
    }).compose(openResourceHandler -> {
      result.isOpen = openResourceHandler.equalsIgnoreCase("OPEN");
      if (result.isOpen && OPEN_ENDPOINTS.contains(endPoint)) {
        JsonObject json = new JsonObject();
        json.put(JSON_CONSUMER, result.jwtData.getSub());
        handler.handle(Future.succeededFuture(json));
      }
      return isValidId(result.jwtData, id);
    }).compose(validIdHandler -> {
      return validateAccess(result.jwtData, result.isResourceExist, authenticationInfo);
    }).onComplete(completeHandler -> {
      LOGGER.debug("completion handler");
      if (completeHandler.succeeded()) {
        handler.handle(Future.succeededFuture(completeHandler.result()));
      } else {
        LOGGER.error("error : " + completeHandler.cause().getMessage());
        handler.handle(Future.failedFuture(completeHandler.cause().getMessage()));
      }
    });

    return this;
  }

  // class to contain intermeddiate data for token interospection
  final class ResultContainer {
    JwtData jwtData;
    boolean isResourceExist;
    boolean isOpen;
  }


  Future<JwtData> decodeJwt(String jwtToken) {
    Promise<JwtData> promise = Promise.promise();

    // jwtToken =
    // "eyJ0eXAiOiJKV1QiLCJhbGciOiJFUzI1NiJ9.eyJzdWIiOiIzNDliNGI1NS0wMjUxLTQ5MGUtYmVlOS0wMGYzYTVkM2U2NDMiLCJpc3MiOiJhdXRoLnRlc3QuY29tIiwiYXVkIjoiZm9vYmFyLml1ZHguaW8iLCJleHAiOjE2MjU5NDUxMTQsImlhdCI6MTYyNTkwMTkxNCwiaWlkIjoicmc6ZXhhbXBsZS5jb20vOGQ0YjIwZWM0YmYyMWVmYjM2M2U3MjY3MWUxYjViZDc3ZmQ2Y2Y5MS9yZXNvdXJjZS1ncm91cCIsInJvbGUiOiJjb25zdW1lciIsImNvbnMiOnt9fQ.44MehPzbPBgAFWz7k3CSF2b-wHBQktGVJVk-unDLnO3_SrbClyQ3k42PgD7TFKB9H13rqBegr7vI0C4BShZbAw";

    TokenCredentials creds = new TokenCredentials(jwtToken);

    jwtAuth.authenticate(creds)
        .onSuccess(user -> {
          JwtData jwtData = new JwtData(user.principal());
          promise.complete(jwtData);
        }).onFailure(err -> {
          LOGGER.error("failed to decode/validate jwt token : " + err.getMessage());
          promise.fail("failed");
        });

    return promise.future();
  }

  private Future<String> isOpenResource(String id) {
    LOGGER.debug("isOpenResource() started");
    Promise<String> promise = Promise.promise();

    String ACL = resourceIdCache.getIfPresent(id);
    if (ACL != null) {
      LOGGER.debug("Cache Hit");
      promise.complete(ACL);
    } else {
      // cache miss
      LOGGER.debug("Cache miss calling cat server");
      String[] idComponents = id.split("/");
      if (idComponents.length < 4) {
        promise.fail("Not Found " + id);
      }
      String groupId = (idComponents.length == 4) ? id
          : String.join("/", Arrays.copyOfRange(idComponents, 0, 4));
      // 1. check group accessPolicy.
      // 2. check resource exist, if exist set accessPolicy to group accessPolicy. else fail
      Future<String> groupACLFuture = getGroupAccessPolicy(groupId);
      groupACLFuture.compose(groupACLResult -> {
        String groupPolicy = (String) groupACLResult;
        return isResourceExist(id, groupPolicy);
      }).onSuccess(handler -> {
        promise.complete(resourceIdCache.getIfPresent(id));
      }).onFailure(handler -> {
        LOGGER.error("cat response failed for Id : (" + id + ")" + handler.getCause());
        promise.fail("Not Found " + id);
      });
    }
    return promise.future();
  }

  public Future<JsonObject> validateAccess(JwtData jwtData, boolean resourceExist, JsonObject authInfo) {
    LOGGER.trace("validateAccess() started");
    Promise<JsonObject> promise = Promise.promise();

    Method method = Method.valueOf(authInfo.getString("method"));
    Api api = Api.fromEndpoint(authInfo.getString("apiEndpoint"));
    AuthorizationRequest authRequest = new AuthorizationRequest(method, api);

    AuthorizationStrategy authStrategy = AuthorizationContextFactory.create(jwtData.getRole());
    LOGGER.info("strategy : " + authStrategy.getClass().getSimpleName());
    JwtAuthorization jwtAuthStrategy = new JwtAuthorization(authStrategy);
    LOGGER.info("endPoint : " + authInfo.getString("apiEndpoint"));
    if (jwtAuthStrategy.isAuthorized(authRequest, jwtData)) {
      LOGGER.info("User access is allowed.");
      JsonObject jsonResponse = new JsonObject();
      jsonResponse.put(JSON_CONSUMER, jwtData.getSub());
      promise.complete(jsonResponse);
    } else {
      LOGGER.info("failed");
      JsonObject result = new JsonObject().put("401", "no access provided to endpoint");
      promise.fail(result.toString());
    }
    return promise.future();
  }

  Future<Boolean> isValidAudienceValue(JwtData jwtData) {
    Promise<Boolean> promise = Promise.promise();
    if (audience != null && audience.equalsIgnoreCase(jwtData.getAud())) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect audience value in jwt");
      promise.fail("Incorrect audience value in jwt");
    }
    return promise.future();
  }

  Future<Boolean> isValidIssuerValue(JwtData jwtData) {
    Promise<Boolean> promise = Promise.promise();

    if (issuer != null && issuer.equalsIgnoreCase(jwtData.getIss())) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect issuer value in jwt");
      promise.fail("Incorrect issuer value in jwt");
    }

    return promise.future();
  }

  Future<Boolean> isValidId(JwtData jwtData, String id) {
    Promise<Boolean> promise = Promise.promise();
    String jwtId = jwtData.getIid().split(":")[1];
    if (id.equalsIgnoreCase(jwtId)) {
      promise.complete(true);
    } else {
      LOGGER.error("Incorrect id value in jwt");
      promise.fail("Incorrect id value in jwt");
    }

    return promise.future();
  }

  private Future<Boolean> isItemExist(String itemId) {
    LOGGER.debug("isItemExist() started");
    Promise<Boolean> promise = Promise.promise();
    String id = itemId.replace("/*", "");
    LOGGER.info("id : " + id);
    catWebClient.get(port, host, "/iudx/cat/v1/item").addQueryParam("id", id)
        .expect(ResponsePredicate.JSON).send(responseHandler -> {
          if (responseHandler.succeeded()) {
            HttpResponse<Buffer> response = responseHandler.result();
            JsonObject responseBody = response.bodyAsJsonObject();
            if (responseBody.getString("status").equalsIgnoreCase("success")
                && responseBody.getInteger("totalHits") > 0) {
              promise.complete(true);
            } else {
              promise.fail(responseHandler.cause());
            }
          } else {
            promise.fail(responseHandler.cause());
          }
        });
    return promise.future();
  }

  private Future<Boolean> isResourceExist(String id, String groupACL) {
    LOGGER.debug("isResourceExist() started");
    Promise<Boolean> promise = Promise.promise();
    String resourceExist = resourceIdCache.getIfPresent(id);
    if (resourceExist != null) {
      LOGGER.debug("Info : cache Hit");
      promise.complete(true);
    } else {
      LOGGER.debug("Info : Cache miss : call cat server");
      catWebClient.get(port, host, path).addQueryParam("property", "[id]")
          .addQueryParam("value", "[[" + id + "]]").addQueryParam("filter", "[id]")
          .expect(ResponsePredicate.JSON).send(responseHandler -> {
            if (responseHandler.failed()) {
              promise.fail("false");
            }
            HttpResponse<Buffer> response = responseHandler.result();
            JsonObject responseBody = response.bodyAsJsonObject();
            if (response.statusCode() != HttpStatus.SC_OK) {
              promise.fail("false");
            } else if (!responseBody.getString("status").equals("success")) {
              promise.fail("Not Found");
              return;
            } else if (responseBody.getInteger("totalHits") == 0) {
              LOGGER.debug("Info: Resource ID invalid : Catalogue item Not Found");
              promise.fail("Not Found");
            } else {
              LOGGER.debug("is Exist response : " + responseBody);
              resourceIdCache.put(id, groupACL);
              promise.complete(true);
            }
          });
    }
    return promise.future();
  }

  private Future<String> getGroupAccessPolicy(String groupId) {
    LOGGER.debug("getGroupAccessPolicy() started");
    Promise<String> promise = Promise.promise();
    String groupACL = resourceGroupCache.getIfPresent(groupId);
    if (groupACL != null) {
      LOGGER.debug("Info : cache Hit");
      promise.complete(groupACL);
    } else {
      LOGGER.debug("Info : cache miss");
      catWebClient.get(port, host, path).addQueryParam("property", "[id]")
          .addQueryParam("value", "[[" + groupId + "]]").addQueryParam("filter", "[accessPolicy]")
          .expect(ResponsePredicate.JSON).send(httpResponseAsyncResult -> {
            if (httpResponseAsyncResult.failed()) {
              LOGGER.error(httpResponseAsyncResult.cause());
              promise.fail("Resource not found");
              return;
            }
            HttpResponse<Buffer> response = httpResponseAsyncResult.result();
            if (response.statusCode() != HttpStatus.SC_OK) {
              promise.fail("Resource not found");
              return;
            }
            JsonObject responseBody = response.bodyAsJsonObject();
            if (!responseBody.getString("status").equals("success")) {
              promise.fail("Resource not found");
              return;
            }
            String resourceACL = "SECURE";
            try {
              resourceACL =
                  responseBody.getJsonArray("results").getJsonObject(0).getString("accessPolicy");
              resourceGroupCache.put(groupId, resourceACL);
              LOGGER.debug("Info: Group ID valid : Catalogue item Found");
              promise.complete(resourceACL);
            } catch (Exception ignored) {
              LOGGER.error(ignored.getMessage());
              LOGGER.debug("Info: Group ID invalid : Empty response in results from Catalogue");
              promise.fail("Resource not found");
            }
          });
    }
    return promise.future();
  }

  private AuthenticationService authenticateAdminRole(JwtData jwtData, Handler<AsyncResult<JsonObject>> handler) {
    isValidAudienceValue(jwtData)
        .compose(ar -> {
          LOGGER.debug("Audience Value validated");
          return isValidIssuerValue(jwtData);
        })
        .onSuccess(ar -> {
          LOGGER.debug("Info: Admin role validated");
          handler.handle(Future.succeededFuture());
        })
        .onFailure(ar -> {
          LOGGER.debug("Error: could not verify admin role due to: {}", ar.getMessage());
          handler.handle(Future.failedFuture(ar.getCause()));
        });
    return this;
  }

}
