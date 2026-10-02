;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.browser-history-test
  (:require
   [app.util.browser-history :as bh]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

(defn- fake-browser
  "A browser handle map whose `location.search` is `search`, and a
  `history` that records every write and keeps `search` in sync. The
  returned atom holds `{:calls [...] :search \"...\"}` so a test can
  assert on both the write and the resulting token."
  [search]
  (let [state (atom {:search search :calls []})]
    [state
     {:location #js {:search search}
      :history  #js {:pushState    (fn [_ _ url]
                                     (swap! state assoc :search (subs url 1))
                                     (swap! state update :calls conj [:push url]))
                     :replaceState (fn [_ _ url]
                                     (swap! state assoc :search (subs url 1))
                                     (swap! state update :calls conj [:replace url]))}}]))

(defn- tokens-reported
  "Runs `f` and returns the tokens it reported on `token-changes`, in
  order. That subject is a module-level stream shared with the app, so
  the subscription is dropped again once the collected sequence is
  realised. Note `rx/subs!` takes the callbacks before the observable."
  [f]
  (let [reported (atom [])
        sub      (rx/subs! #(swap! reported conj %) bh/token-changes)]
    (try
      (f)
      (finally
        (rx/dispose! sub)))
    @reported))

(t/deftest get-token-reads-the-current-query-string
  (let [[_state handles] (fake-browser "?screen=workspace")]
    (binding [bh/*browser* handles]
      (t/is (= "?screen=workspace" (bh/get-token))))))

(t/deftest get-token-is-empty-when-the-url-has-no-query
  ;; `location.search` is `""`, never nil, on a URL with no query.
  (let [[_state handles] (fake-browser "")]
    (binding [bh/*browser* handles]
      (t/is (= "" (bh/get-token))))))

(t/deftest url-appends-the-token-to-the-base-path
  ;; The path stays the application base; only the query carries the token.
  (t/is (= "/?screen=workspace&team-id=t1"
           (bh/url "/" "?screen=workspace&team-id=t1")))
  (t/is (= "/design/?screen=viewer" (bh/url "/design/" "?screen=viewer")))
  (t/is (= "/" (bh/url "/" ""))))

(t/deftest set-token-pushes-a-new-entry-and-reports-it
  (let [[state handles] (fake-browser "?screen=dashboard-recent")
        reported        (tokens-reported
                         #(binding [bh/*browser* handles]
                            (bh/set-token! "/"
                                           "?screen=workspace&team-id=t1")))]
    (t/is (= [[:push "/?screen=workspace&team-id=t1"]] (:calls @state)))
    (t/is (= ["?screen=workspace&team-id=t1"] reported))))

(t/deftest set-token-keeps-the-path-on-the-base
  (let [[state handles] (fake-browser "")]
    (binding [bh/*browser* handles]
      (bh/set-token! "/design/" "?screen=viewer"))
    (let [[[_op written-url]] (:calls @state)]
      (t/is (= "/design/?screen=viewer" written-url)))))

(t/deftest set-token-does-nothing-when-the-token-already-is-current
  ;; Navigating to the screen you are on must not grow the history stack,
  ;; so neither the URL nor the subscribers see a change.
  (let [[state handles] (fake-browser "?screen=dashboard-recent")
        reported        (tokens-reported
                         #(binding [bh/*browser* handles]
                            (bh/set-token! "/" "?screen=dashboard-recent")))]
    (t/is (= [] (:calls @state)))
    (t/is (= [] reported))))

(t/deftest replace-token-always-writes-and-reports-on-a-new-token
  (let [[state handles] (fake-browser "?screen=dashboard-recent")
        reported        (tokens-reported
                         #(binding [bh/*browser* handles]
                            (bh/replace-token! "/" "?screen=auth-login")))]
    (t/is (= [[:replace "/?screen=auth-login"]] (:calls @state)))
    (t/is (= ["?screen=auth-login"] reported))))

(t/deftest replace-token-also-writes-and-reports-the-current-token
  ;; Replacing corrects the URL in place — legacy `#/…` translation, a
  ;; cleaned share link — so it has to reach the router even when the
  ;; token did not change. That is the deliberate difference against
  ;; `set-token!`.
  (let [[state handles] (fake-browser "?screen=dashboard-recent")
        reported        (tokens-reported
                         #(binding [bh/*browser* handles]
                            (bh/replace-token! "/" "?screen=dashboard-recent")))]
    (t/is (= [[:replace "/?screen=dashboard-recent"]] (:calls @state)))
    (t/is (= ["?screen=dashboard-recent"] reported))))
