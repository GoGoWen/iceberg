/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iceberg.rest;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.SupportsNamespaces;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.ForbiddenException;
import org.apache.iceberg.exceptions.NamespaceNotEmptyException;
import org.apache.iceberg.exceptions.NoSuchIcebergTableException;
import org.apache.iceberg.exceptions.NoSuchNamespaceException;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.exceptions.NotAuthorizedException;
import org.apache.iceberg.exceptions.RESTException;
import org.apache.iceberg.exceptions.UnprocessableEntityException;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.relocated.com.google.common.base.Splitter;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.rest.requests.CreateNamespaceRequest;
import org.apache.iceberg.rest.requests.CreateTableRequest;
import org.apache.iceberg.rest.requests.UpdateNamespacePropertiesRequest;
import org.apache.iceberg.rest.requests.UpdateTableRequest;
import org.apache.iceberg.rest.responses.ErrorResponse;
import org.apache.iceberg.rest.responses.RESTCatalogConfigResponse;
import org.apache.iceberg.util.Pair;

/**
 * Adaptor class to translate REST requests into {@link Catalog} API calls.
 */
public class RESTCatalogAdapter implements RESTClient {
  private static final Splitter SLASH = Splitter.on('/');

  private static final Map<Class<? extends Exception>, Integer> EXCEPTION_ERROR_CODES = ImmutableMap
      .<Class<? extends Exception>, Integer>builder()
      .put(IllegalArgumentException.class, 400)
      .put(ValidationException.class, 400)
      .put(NamespaceNotEmptyException.class, 400) // TODO: should this be more specific?
      .put(NotAuthorizedException.class, 401)
      .put(ForbiddenException.class, 403)
      .put(NoSuchNamespaceException.class, 404)
      .put(NoSuchTableException.class, 404)
      .put(NoSuchIcebergTableException.class, 404)
      .put(UnsupportedOperationException.class, 406)
      .put(AlreadyExistsException.class, 409)
      .put(CommitFailedException.class, 409)
      .put(UnprocessableEntityException.class, 422)
      .put(CommitStateUnknownException.class, 500)
      .build();

  private final Catalog catalog;
  private final SupportsNamespaces asNamespaceCatalog;

  public RESTCatalogAdapter(Catalog catalog) {
    this.catalog = catalog;
    this.asNamespaceCatalog = catalog instanceof SupportsNamespaces ? (SupportsNamespaces) catalog : null;
  }

  private enum HTTPMethod {
    GET,
    HEAD,
    POST,
    DELETE
  }

  private enum Route {
    CONFIG(HTTPMethod.GET, "v1/config"),
    LIST_NAMESPACES(HTTPMethod.GET, "v1/namespaces"),
    CREATE_NAMESPACE(HTTPMethod.POST, "v1/namespaces"),
    LOAD_NAMESPACE(HTTPMethod.GET, "v1/namespaces/{namespace}"),
    DROP_NAMESPACE(HTTPMethod.DELETE, "v1/namespaces/{namespace}"),
    UPDATE_NAMESPACE(HTTPMethod.POST, "v1/namespaces/{namespace}/properties"),
    LIST_TABLES(HTTPMethod.GET, "v1/namespaces/{namespace}/tables"),
    CREATE_TABLE(HTTPMethod.POST, "v1/namespaces/{namespace}/tables"),
    LOAD_TABLE(HTTPMethod.GET, "v1/namespaces/{namespace}/tables/{table}"),
    UPDATE_TABLE(HTTPMethod.POST, "v1/namespaces/{namespace}/tables/{table}"),
    DROP_TABLE(HTTPMethod.DELETE, "v1/namespaces/{namespace}/tables/{table}");

    private final HTTPMethod method;
    private final int requriedLength;
    private final Map<Integer, String> requirements;
    private final Map<Integer, String> variables;

    Route(HTTPMethod method, String pattern) {
      this.method = method;

      // parse the pattern into requirements and variables
      List<String> parts = SLASH.splitToList(pattern);
      ImmutableMap.Builder<Integer, String> requirementsBuilder = ImmutableMap.builder();
      ImmutableMap.Builder<Integer, String> variablesBuilder = ImmutableMap.builder();
      for (int pos = 0; pos < parts.size(); pos += 1) {
        String part = parts.get(pos);
        if (part.startsWith("{") && part.endsWith("}")) {
          variablesBuilder.put(pos, part.substring(1, part.length() - 1));
        } else {
          requirementsBuilder.put(pos, part);
        }
      }

      this.requriedLength = parts.size();
      this.requirements = requirementsBuilder.build();
      this.variables = variablesBuilder.build();
    }

    private boolean matches(HTTPMethod requestMethod, List<String> requestPath) {
      return method == requestMethod && requriedLength == requestPath.size() &&
          requirements.entrySet().stream().allMatch(
              requirement -> requirement.getValue().equalsIgnoreCase(requestPath.get(requirement.getKey())));
    }

    private Map<String, String> variables(List<String> requestPath) {
      ImmutableMap.Builder<String, String> vars = ImmutableMap.builder();
      variables.forEach((key, value) -> vars.put(value, requestPath.get(key)));
      return vars.build();
    }

    public static Pair<Route, Map<String, String>> from(HTTPMethod method, String path) {
      List<String> parts = SLASH.splitToList(path);
      for (Route candidate : Route.values()) {
        if (candidate.matches(method, parts)) {
          return Pair.of(candidate, candidate.variables(parts));
        }
      }

      return null;
    }
  }

  public <T extends RESTResponse> T handleRequest(Route route, Map<String, String> vars,
                                                  Object body, Class<T> responseType) {
    switch (route) {
      case CONFIG:
        return castResponse(responseType, RESTCatalogConfigResponse.builder().build());

      case LIST_NAMESPACES:
        if (asNamespaceCatalog != null) {
          // TODO: support parent namespace from query params
          return castResponse(responseType, CatalogHandlers.listNamespaces(asNamespaceCatalog, Namespace.empty()));
        }
        break;

      case CREATE_NAMESPACE:
        if (asNamespaceCatalog != null) {
          CreateNamespaceRequest request = castRequest(CreateNamespaceRequest.class, body);
          return castResponse(responseType, CatalogHandlers.createNamespace(asNamespaceCatalog, request));
        }
        break;

      case LOAD_NAMESPACE:
        if (asNamespaceCatalog != null) {
          Namespace namespace = namespaceFromPathVars(vars);
          return castResponse(responseType, CatalogHandlers.loadNamespace(asNamespaceCatalog, namespace));
        }
        break;

      case DROP_NAMESPACE:
        if (asNamespaceCatalog != null) {
          CatalogHandlers.dropNamespace(asNamespaceCatalog, namespaceFromPathVars(vars));
          return null;
        }
        break;

      case UPDATE_NAMESPACE:
        if (asNamespaceCatalog != null) {
          Namespace namespace = namespaceFromPathVars(vars);
          UpdateNamespacePropertiesRequest request = castRequest(UpdateNamespacePropertiesRequest.class, body);
          return castResponse(responseType,
              CatalogHandlers.updateNamespaceProperties(asNamespaceCatalog, namespace, request));
        }
        break;

      case LIST_TABLES: {
        Namespace namespace = namespaceFromPathVars(vars);
        return castResponse(responseType, CatalogHandlers.listTables(catalog, namespace));
      }

      case CREATE_TABLE: {
        Namespace namespace = namespaceFromPathVars(vars);
        CreateTableRequest request = castRequest(CreateTableRequest.class, body);
        request.validate();
        if (request.stageCreate()) {
          return castResponse(responseType, CatalogHandlers.stageTableCreate(catalog, namespace, request));
        } else {
          return castResponse(responseType, CatalogHandlers.createTable(catalog, namespace, request));
        }
      }

      case DROP_TABLE: {
        CatalogHandlers.dropTable(catalog, identFromPathVars(vars));
        return null;
      }

      case LOAD_TABLE: {
        TableIdentifier ident = identFromPathVars(vars);
        return castResponse(responseType, CatalogHandlers.loadTable(catalog, ident));
      }

      case UPDATE_TABLE: {
        TableIdentifier ident = identFromPathVars(vars);
        UpdateTableRequest request = castRequest(UpdateTableRequest.class, body);
        return castResponse(responseType, CatalogHandlers.updateTable(catalog, ident, request));
      }

      default:
    }

    return null;
  }

  public <T extends RESTResponse> T execute(HTTPMethod method, String path, Object body, Class<T> responseType,
                                            Consumer<ErrorResponse> errorHandler) {
    ErrorResponse.Builder errorBuilder = ErrorResponse.builder();
    Pair<Route, Map<String, String>> routeAndVars = Route.from(method, path);
    if (routeAndVars != null) {
      try {
        return handleRequest(routeAndVars.first(), routeAndVars.second(), body, responseType);

      } catch (RuntimeException e) {
        configureResponseFromException(e, errorBuilder);
      }

    } else {
      errorBuilder
          .responseCode(400)
          .withType("BadRequestException")
          .withMessage(String.format("No route for request: %s %s", method, path));
    }

    ErrorResponse error = errorBuilder.build();
    errorHandler.accept(error);

    // if the error handler doesn't throw an exception, throw a generic one
    throw new RESTException("Unhandled error: %s", error);
  }

  @Override
  public <T extends RESTResponse> T delete(String path, Class<T> responseType, Consumer<ErrorResponse> errorHandler) {
    return execute(HTTPMethod.DELETE, path, null, responseType, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T post(String path, RESTRequest body, Class<T> responseType,
                                         Consumer<ErrorResponse> errorHandler) {
    return execute(HTTPMethod.POST, path, body, responseType, errorHandler);
  }

  @Override
  public <T extends RESTResponse> T get(String path, Class<T> responseType, Consumer<ErrorResponse> errorHandler) {
    return execute(HTTPMethod.GET, path, null, responseType, errorHandler);
  }

  @Override
  public void head(String path, Consumer<ErrorResponse> errorHandler) {
    execute(HTTPMethod.HEAD, path, null, null, errorHandler);
  }

  @Override
  public void close() throws IOException {
    // The calling test is responsible for closing the underlying catalog backing this REST catalog
    // so that the underlying backend catalog is not closed and reopened during the REST catalog's
    // initialize method when fetching the server configuration.
  }

  private static class BadResponseType extends RuntimeException {
    private BadResponseType(Class<?> responseType, Object response) {
      super(String.format("Invalid response object, not a %s: %s", responseType.getName(), response));
    }
  }

  private static class BadRequestType extends RuntimeException {
    private BadRequestType(Class<?> requestType, Object request) {
      super(String.format("Invalid request object, not a %s: %s", requestType.getName(), request));
    }
  }

  public static <T> T castRequest(Class<T> requestType, Object request) {
    if (requestType.isInstance(request)) {
      return requestType.cast(request);
    }

    throw new BadRequestType(requestType, request);
  }

  public static <T extends RESTResponse> T castResponse(Class<T> responseType, Object response) {
    if (responseType.isInstance(response)) {
      return responseType.cast(response);
    }

    throw new BadResponseType(responseType, response);
  }

  public static void configureResponseFromException(Exception exc, ErrorResponse.Builder errorBuilder) {
    errorBuilder
        .responseCode(EXCEPTION_ERROR_CODES.getOrDefault(exc.getClass(), 500))
        .withType(exc.getClass().getSimpleName())
        .withMessage(exc.getMessage())
        .withStackTrace(exc);
  }

  private static Namespace namespaceFromPathVars(Map<String, String> pathVars) {
    return RESTUtil.decodeNamespace(pathVars.get("namespace"));
  }

  private static TableIdentifier identFromPathVars(Map<String, String> pathVars) {
    return TableIdentifier.of(namespaceFromPathVars(pathVars), RESTUtil.decodeString(pathVars.get("table")));
  }
}
