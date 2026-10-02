;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.browser-history
  "Query-string browser history.

  The history token is the query string (`?screen=<name>&params`) and the
  path always stays the application base, so the URL of a token is just
  the application base followed by the token.

  Replaces `goog.history.Html5History`, which is a fragment (hash)
  router. Once fragment navigation was dropped, its token, URL and
  popstate logic reduced to `location.search` plus
  `pushState`/`replaceState`, while it kept a `hashchange` listener,
  gated popstate on a fragment that was then always `null`, and
  asserted on `history.pushState` in its constructor, which made it
  unusable outside a browser and therefore untestable."
  (:require
   [app.common.data.macros :as dm]
   [app.util.globals :as globals]
   [beicon.v2.core :as rx]))

(def ^:dynamic *browser*
  "The browser handles this namespace needs. Bound in tests, so they can
  drive a fake History API and assert on the URLs written."
  {:location globals/location
   :history  (.-history globals/window)})

(defn get-token
  "The current history token: the query string, `\"\"` when the URL
  carries none."
  []
  (or (.-search ^js (:location *browser*)) ""))

(defn url
  "The URL of `token` under `path-prefix`, the application base path.
  The path never carries the token; only the query string does."
  [path-prefix token]
  (dm/str path-prefix token))

(def token-changes
  "Emits the new token on every history change.

  `pushState` and `replaceState` never fire `popstate`, so this
  namespace reports its own writes here too. Subscribers cannot tell the
  two apart, and do not need to."
  (rx/subject))

(defn- report!
  [token]
  (rx/push! token-changes token))

(defn set-token!
  "Set `token` as a new history entry and report it.

  Does nothing when `token` already is the current one: navigating to
  the screen you are on must not grow the history stack, and there is
  nothing new for a subscriber to re-read from the URL."
  [path-prefix token]
  (when-not (= token (get-token))
    (.pushState ^js (:history *browser*) nil "" (url path-prefix token))
    (report! token)))

(defn replace-token!
  "Replace the current history entry with `token` and report it.

  Unlike [[set-token!]], this always writes and always reports. A replace
  is how the app corrects the URL in place — legacy `#/…` translation, a
  cleaned share link — and that correction has to reach the router even
  when the token did not change."
  [path-prefix token]
  (.replaceState ^js (:history *browser*) nil "" (url path-prefix token))
  (report! token))

;; Back/forward. A `popstate` event carries no token, so re-read it from
;; the URL. Installed once: the application has a single history.
(rx/sub! (->> (rx/from-event globals/window "popstate")
              (rx/map (fn [_] (get-token))))
         token-changes)
