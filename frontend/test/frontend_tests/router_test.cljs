;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.router-test
  (:require
   [app.main.router :as rt]
   [app.util.globals :as globals]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(t/deftest match-context-params-file-link
  ;; Workspace and viewer links only mirror the file-id.
  (let [match {:query-params {:team-id "team-1"
                              :file-id "file-1"
                              :page-id "page-1"}}]
    (t/is (= {:file-id "file-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-file-link-path-params
  ;; Legacy routes carry the ids as path params.
  (let [match {:params {:path {:project-id "project-1"
                               :file-id "file-1"}}}]
    (t/is (= {:file-id "file-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-project-link
  (let [match {:query-params {:team-id "team-1"
                              :project-id "project-1"}}]
    (t/is (= {:team-id "team-1"
              :project-id "project-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-team-link
  (let [match {:query-params {:team-id "team-1"}}]
    (t/is (= {:team-id "team-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-no-context
  (let [match {:query-params {:token "some-token"}}]
    (t/is (nil? (rt/match->context-params match)))))

(t/deftest match-context-params-project-link-without-team
  ;; Without a team-id the raw map keeps a nil team-id; the nil is dropped
  ;; later by the query-string serialization, not here.
  (let [match {:query-params {:project-id "project-1"}}]
    (t/is (= {:team-id nil
              :project-id "project-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-file-beats-project-and-team
  ;; With file, project and team ids present, only the file-id is mirrored.
  (let [match {:query-params {:team-id "team-1"
                              :project-id "project-1"
                              :file-id "file-1"}}]
    (t/is (= {:file-id "file-1"}
             (rt/match->context-params match)))))

(t/deftest match-context-params-repeated-key
  ;; A repeated query key arrives as a vector; the last value wins.
  (let [match {:query-params {:file-id ["file-old" "file-1"]}}]
    (t/is (= {:file-id "file-1"}
             (rt/match->context-params match)))))

(defn- with-stubbed-browser
  "Run `thunk` with the `globals/location` mock props and a recording
  `js/history.replaceState`. Restores both originals afterwards: the
  location mock is shared across tests and `js/history` may not exist
  in the test environment at all."
  [{:keys [pathname search hash href]} replace-calls thunk]
  (let [loc          globals/location
        old-pathname (.-pathname loc)
        old-search   (.-search loc)
        old-hash     (.-hash loc)
        old-href     (.-href loc)
        old-history  (.-history js/globalThis)]
    (set! (.-pathname loc) pathname)
    (set! (.-search loc) search)
    (set! (.-hash loc) hash)
    (set! (.-href loc) href)
    (set! (.-history js/globalThis)
          #js {:replaceState (fn [_ _ url] (swap! replace-calls conj url))})
    (try
      (thunk)
      (finally
        (set! (.-pathname loc) old-pathname)
        (set! (.-search loc) old-search)
        (set! (.-hash loc) old-hash)
        (set! (.-href loc) old-href)
        (set! (.-history js/globalThis) old-history)))))

(t/deftest navigated-mirrors-context-on-change
  ;; New context in the match triggers exactly one mirrored write.
  (let [calls (atom [])]
    (with-stubbed-browser
      {:pathname "/" :search "" :hash "#/workspace?file-id=file-1" :href "http://localhost/"}
      calls
      (fn []
        (ptk/effect (rt/navigated {:query-params {:file-id "file-1"}} false) nil nil)
        (t/is (= ["/?file-id=file-1#/workspace?file-id=file-1"] @calls))))))

(t/deftest navigated-skips-write-when-mirrored
  ;; When the URL already carries the mirrored context, nothing is written.
  (let [calls (atom [])]
    (with-stubbed-browser
      {:pathname "/" :search "?file-id=file-1" :hash "#/workspace?file-id=file-1" :href "http://localhost/?file-id=file-1#/workspace?file-id=file-1"}
      calls
      (fn []
        (ptk/effect (rt/navigated {:query-params {:file-id "file-1"}} false) nil nil)
        (t/is (= [] @calls))))))

(t/deftest navigated-strips-stale-context
  ;; A stale pre-fragment query is replaced with the current context.
  (let [calls (atom [])]
    (with-stubbed-browser
      {:pathname "/" :search "?file-id=old" :hash "#/dashboard/recent?team-id=team-1" :href "http://localhost/?file-id=old#/dashboard/recent?team-id=team-1"}
      calls
      (fn []
        (ptk/effect (rt/navigated {:query-params {:team-id "team-1"}} false) nil nil)
        (t/is (= ["/?team-id=team-1#/dashboard/recent?team-id=team-1"] @calls))))))
