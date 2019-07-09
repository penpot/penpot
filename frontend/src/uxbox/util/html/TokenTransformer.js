/**
 * TokenTransformer
 *
 * @author Paul Anderson <paul@andersonpaul.com>, 2018
 * @license BSD License <https://opensource.org/licenses/BSD-2-Clause>
 */

goog.provide('uxbox.util.html.TokenTransformer');
goog.require('goog.history.Html5History');

goog.scope(function() {
  /**
   * A goog.history.Html5History.TokenTransformer implementation that
   * includes the query string in the token.
   *
   * The implementation of token<->url transforms in
   * `goog.history.Html5History`, when useFragment is false and no custom
   * transformer is supplied, assumes that a token is equivalent to
   * `window.location.pathname` minus any configured path prefix. Since
   * bide allows constructing urls that include a query string, we want
   * to be able to store those as tokens.
   *
   * Addresses funcool/bide#15.
   *
   * @constructor
   * @implements {goog.history.Html5History.TokenTransformer}
   */
  uxbox.util.html.TokenTransformer = function () {};

  /**
   * Retrieves a history token given the path prefix and
   * `window.location` object.
   *
   * @param {string} pathPrefix The path prefix to use when storing token
   *     in a path; always begin with a slash.
   * @param {Location} location The `window.location` object.
   *     Treat this object as read-only.
   * @return {string} token The history token.
   */
  uxbox.util.html.TokenTransformer.prototype.retrieveToken = function(pathPrefix, location) {
    return location.pathname.substr(pathPrefix.length) + location.search;
  };

  /**
   * Creates a URL to be pushed into HTML5 history stack when storing
   * token without using hash fragment.
   *
   * @param {string} token The history token.
   * @param {string} pathPrefix The path prefix to use when storing token
   *     in a path; always begin with a slash.
   * @param {Location} location The `window.location` object.
   *     Treat this object as read-only.
   * @return {string} url The complete URL string from path onwards
   *     (without {@code protocol://host:port} part); must begin with a
   *     slash.
   */
  uxbox.util.html.TokenTransformer.prototype.createUrl = function(token, pathPrefix, location) {
    return pathPrefix + token;
  };
});
