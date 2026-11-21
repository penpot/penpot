;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.progress-test
  "The dialog that blocks the workspace while the token values are set
  (`app.main.data.workspace.tokens.progress`)."
  (:require
   [app.common.time :as ct]
   [app.main.data.workspace.tokens.progress :as dwtpr]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(t/deftest the-progress-shows-until-the-work-is-done
  (let [tick    #'dwtpr/tick
        long    (js/Date. (- (js/Date.now) 5000))
        work*   (volatile! {:total 2 :done 0 :quiet 0 :started long})
        type-of (fn [events]
                  (let [first* (atom nil)]
                    (when events
                      (rx/sub! (rx/take 1 events) #(reset! first* (ptk/type %))))
                    @first*))]
    (t/is (= ::dwtpr/show (type-of (tick work*)))
          "shown once the work runs longer than the delay")
    (vswap! work* update :done + 1)
    (t/is (nil? (tick work*)))
    (vswap! work* update :done + 1)
    (t/is (nil? (tick work*)) "the texts and layouts may follow")
    (t/is (= ::dwtpr/hide (type-of (tick work*)))
          "hidden when the values are set and nothing is pending")
    (t/is (nil? @work*))))

(t/deftest quick-work-shows-no-progress
  (let [tick  #'dwtpr/tick
        work* (volatile! {:total 1 :done 0 :quiet 0 :started (ct/now)})]
    (t/is (nil? (tick work*)))
    (vswap! work* update :done + 1)
    (t/is (nil? (tick work*)))
    (t/is (nil? (tick work*)) "done before the delay")
    (t/is (nil? @work*))))
