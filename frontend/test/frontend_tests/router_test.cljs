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

(defn- with-stubbed-href
  "Run `thunk` with the `globals/location` href replaced and a recording
  `js/history.replaceState`. Only the href is stubbed: the effect parses
  everything it needs out of it."
  [href replace-calls thunk]
  (let [loc         globals/location
        old-href    (.-href loc)
        old-history (.-history js/globalThis)]
    (set! (.-href loc) href)
    (set! (.-history js/globalThis)
          #js {:replaceState (fn [_ _ url] (swap! replace-calls conj url))})
    (try
      (thunk)
      (finally
        (set! (.-href loc) old-href)
        (set! (.-history js/globalThis) old-history)))))

(defn- emit-navigated
  "Run the `navigated` effect with `match` stored as the state route.
  The closed-over match is deliberately empty to prove the effect reads
  the route from the state, not from the closure."
  [match]
  (ptk/effect (rt/navigated {} false) {:route match} nil))

(t/deftest navigated-mirrors-context-on-change
  ;; New context in the state route triggers exactly one mirrored write.
  (let [calls (atom [])]
    (with-stubbed-href
      "http://localhost/#/workspace?file-id=file-1"
      calls
      (fn []
        (emit-navigated {:query-params {:file-id "file-1"}})
        (t/is (= ["http://localhost/?file-id=file-1#/workspace?file-id=file-1"] @calls))))))

(t/deftest navigated-skips-write-when-mirrored
  ;; When the URL already carries the mirrored context, nothing is written.
  (let [calls (atom [])]
    (with-stubbed-href
      "http://localhost/?file-id=file-1#/workspace?file-id=file-1"
      calls
      (fn []
        (emit-navigated {:query-params {:file-id "file-1"}})
        (t/is (= [] @calls))))))

(t/deftest navigated-strips-stale-context
  ;; A stale pre-fragment query is replaced with the current context.
  (let [calls (atom [])]
    (with-stubbed-href
      "http://localhost/?file-id=old#/dashboard/recent?team-id=team-1"
      calls
      (fn []
        (emit-navigated {:query-params {:team-id "team-1"}})
        (t/is (= ["http://localhost/?team-id=team-1#/dashboard/recent?team-id=team-1"] @calls))))))

(t/deftest navigated-clears-query-without-context
  ;; Routes without context clear a stale pre-fragment query.
  (let [calls (atom [])]
    (with-stubbed-href
      "http://localhost/?file-id=old#/auth/login"
      calls
      (fn []
        (emit-navigated {:query-params {:token "some-token"}})
        (t/is (= ["http://localhost/#/auth/login"] @calls))))))

(t/deftest navigated-preserves-unrelated-params
  ;; Params owned by other code are kept as they are.
  (let [calls (atom [])]
    (with-stubbed-href
      "http://localhost/?debug=1&file-id=old#/workspace?file-id=file-1"
      calls
      (fn []
        (emit-navigated {:query-params {:file-id "file-1"}})
        (t/is (= ["http://localhost/?debug=1&file-id=file-1#/workspace?file-id=file-1"] @calls))))))

(t/deftest navigated-mirrors-every-present-context-id
  ;; Every present context id is mirrored; the backend applies its own
  ;; file > project > team priority, so no filtering happens here.
  (let [calls (atom [])]
    (with-stubbed-href
      "http://localhost/#/workspace?file-id=file-1&team-id=team-1&project-id=project-1&page-id=page-1"
      calls
      (fn []
        (emit-navigated {:query-params {:file-id "file-1"
                                        :team-id "team-1"
                                        :project-id "project-1"
                                        :page-id "page-1"}})
        (t/is (= ["http://localhost/?file-id=file-1&team-id=team-1&project-id=project-1#/workspace?file-id=file-1&team-id=team-1&project-id=project-1&page-id=page-1"]
                 @calls))))))

(t/deftest navigated-repeated-key-last-wins
  ;; A repeated query key arrives as a vector; the last value wins.
  (let [calls (atom [])]
    (with-stubbed-href
      "http://localhost/#/workspace?file-id=file-1"
      calls
      (fn []
        (emit-navigated {:query-params {:file-id ["file-old" "file-1"]}})
        (t/is (= ["http://localhost/?file-id=file-1#/workspace?file-id=file-1"] @calls))))))

(t/deftest navigated-keeps-subpath-base
  ;; Under a subpath deployment the prefix survives untouched.
  (let [calls (atom [])]
    (with-stubbed-href
      "http://localhost/penpot/#/workspace?file-id=file-1"
      calls
      (fn []
        (emit-navigated {:query-params {:file-id "file-1"}})
        (t/is (= ["http://localhost/penpot/?file-id=file-1#/workspace?file-id=file-1"] @calls))))))
