;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.readers-test
  "The shapes of the viewer set to the values of their tokens
  (`app.main.data.viewer`)."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.main.data.viewer :as dv]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.propagation :as tohp]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- viewer-radius
  "Runs the viewer resolution of `tokens` with the `saved` values on the
  bundle of `tohp/setup-file` and calls `check` with the radius of its stale and
  legacy shapes."
  [done tokens saved check]
  (let [file    (tohp/setup-file)
        page-id (cthf/current-page-id file)]
    (->> (dv/resolve-token-values {:file file :tokens tokens :tokens-resolved saved} page-id)
         (rx/subs! (fn [bundle]
                     (let [objects (get-in bundle [:file :data :pages-index page-id :objects])]
                       (check (get-in objects [(cthi/id :stale) :r1])
                              (get-in objects [(cthi/id :legacy) :r1]))))
                   (fn [cause]
                     (t/is (nil? cause))
                     (done))
                   done))))

(t/deftest the-viewer-sets-out-of-date-shapes-to-the-resolved-tokens
  (t/async
    done
    (viewer-radius done
                   {"radius" {:name "radius" :type :border-radius :value "{base}"}
                    "base"   {:name "base" :type :border-radius :value "60"}}
                   {"radius" 50}
                   (fn [stale legacy]
                     (t/is (= 60 stale) "the current tokens win over the saved values")
                     (t/is (= 10 legacy) "readers trust unassigned values")))))

(t/deftest the-viewer-uses-the-saved-value-of-a-token-that-does-not-resolve
  (t/async
    done
    (viewer-radius done
                   {"radius" {:name "radius" :type :border-radius :value "{missing}"}}
                   {"radius" 50}
                   (fn [stale _]
                     (t/is (= 50 stale))))))

(t/deftest the-viewer-uses-the-saved-values-without-tokens
  (t/async
    done
    (viewer-radius done nil {"radius" 50}
                   (fn [stale _]
                     (t/is (= 50 stale))))))

(t/deftest the-viewer-sets-only-the-page-it-shows
  (t/async
    done
    (let [file     (tohp/setup-file)
          page1-id (cthf/current-page-id file)
          file     (-> file
                       (cthf/add-sample-page :page2)
                       (ctho/add-frame :stale2)
                       (ctht/apply-token-to-shape :stale2 "radius" [:r1] [:r1] 10)
                       (cths/update-shape :stale2 :token-values {:r1 10}))
          page2-id (cthi/id :page2)
          r1       (fn [bundle page-id label]
                     (get-in bundle [:file :data :pages-index page-id :objects (cthi/id label) :r1]))
          bundle   {:file file :tokens nil :tokens-resolved {"radius" 50}}]
      (->> (rx/zip (dv/resolve-token-values bundle page2-id)
                   (dv/resolve-token-values bundle nil))
           (rx/subs! (fn [[shown first-page]]
                       (t/is (= 50 (r1 shown page2-id :stale2)))
                       (t/is (= 10 (r1 shown page1-id :stale)) "the other pages are left as they are")
                       (t/is (= 50 (r1 first-page page1-id :stale)) "without a page, the first one")
                       (t/is (= 10 (r1 first-page page2-id :stale2))))
                     (fn [cause]
                       (t/is (nil? cause))
                       (done))
                     done)))))
