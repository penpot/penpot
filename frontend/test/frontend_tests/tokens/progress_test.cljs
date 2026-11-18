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
   [app.common.uuid :as uuid]
   [app.main.data.persistence :as-alias dps]
   [app.main.data.workspace.reflow :as wrf]
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

(t/deftest the-progress-does-not-show-once-the-values-are-set
  (wrf/reset-pending!)
  (let [tick  #'dwtpr/tick
        long  (js/Date. (- (js/Date.now) 5000))
        task  (wrf/task :layout [(uuid/next)])
        work* (volatile! {:total 1 :done 1 :quiet 0 :started long})]
    (wrf/start! task)
    (t/is (nil? (tick work*)) "texts and layouts in flight don't show it")
    (t/is (nil? @work*))
    (wrf/finish! task)))

(t/deftest the-progress-waits-only-for-the-work-in-flight-when-the-values-are-set
  (wrf/reset-pending!)
  (let [tick    #'dwtpr/tick
        long    (js/Date. (- (js/Date.now) 5000))
        before  (wrf/task :text-resize [(uuid/next)])
        after   (wrf/task :layout [(uuid/next)])
        work*   (volatile! {:total 1 :done 1 :quiet 0 :started long :shown? true})
        type-of (fn [events]
                  (let [first* (atom nil)]
                    (when events
                      (rx/sub! (rx/take 1 events) #(reset! first* (ptk/type %))))
                    @first*))]
    (wrf/start! before)
    (t/is (nil? (tick work*)) "the work in flight holds it")
    (wrf/start! after)
    (wrf/finish! before)
    (t/is (nil? (tick work*)))
    (t/is (= ::dwtpr/hide (type-of (tick work*)))
          "work started after the values doesn't hold it")
    (wrf/finish! after)))

(t/deftest quick-work-shows-no-progress
  (let [tick  #'dwtpr/tick
        work* (volatile! {:total 1 :done 0 :quiet 0 :started (ct/now)})]
    (t/is (nil? (tick work*)))
    (vswap! work* update :done + 1)
    (t/is (nil? (tick work*)))
    (t/is (nil? (tick work*)) "done before the delay")
    (t/is (nil? @work*))))

(t/deftest the-save-waits-for-the-dialog-to-close
  (t/async
    done
    (let [stream  (rx/subject)
          emitted (atom [])
          watch   (fn [state]
                    (rx/sub! (ptk/watch (dwtpr/persist-when-closed) state stream)
                             #(swap! emitted conj %)))]
      (watch {})
      (t/is (= [::dps/force-persist] @emitted) "at once without the dialog")
      (reset! emitted [])
      (watch {::dwtpr/progress true})
      (t/is (= [] @emitted) "not while the dialog shows")
      (rx/push! stream (#'dwtpr/hide))
      (t/is (= [] @emitted) "not before the closed dialog is painted")
      (js/setTimeout (fn []
                       (t/is (= [::dps/force-persist] @emitted) "once it is painted")
                       (done))
                     (+ dwtpr/paint-timeout-ms 100)))))
