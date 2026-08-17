;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.workspace-comments-test
  (:require
   [app.main.data.comments :as dcmt]
   [app.main.data.workspace.comments :as dwcm]
   [app.main.data.workspace.edition :as dwe]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(t/deftest test-handle-interrupt-draft
  (t/async
    done
    (let [event  (dwcm/handle-interrupt)
          state  {:comments-local {:draft {:id "draft-id"}}}
          result (ptk/watch event state (rx/empty))]
      (->> result
           (rx/subs!
            (fn [evt]
              (t/is (= ::dcmt/close-comment-thread (ptk/type evt))))
            (fn [err]
              (done)
              (js/console.error err)
              (t/do-report {:type :error :message "Stream error" :actual err}))
            (fn [_]
              (done)))))))

(t/deftest test-handle-interrupt-open
  (t/async
    done
    (let [event  (dwcm/handle-interrupt)
          state  {:comments-local {:open {:id "thread-id"}}}
          result (ptk/watch event state (rx/empty))]
      (->> result
           (rx/subs!
            (fn [evt]
              (t/is (= ::dcmt/close-comment-thread (ptk/type evt))))
            (fn [err]
              (done)
              (js/console.error err)
              (t/do-report {:type :error :message "Stream error" :actual err}))
            (fn [_]
              (done)))))))

(t/deftest test-handle-interrupt-comments-mode
  (t/async
    done
    (let [event  (dwcm/handle-interrupt)
          state  {:workspace-drawing {:tool :comments}}
          result (ptk/watch event state (rx/empty))]
      (->> result
           (rx/subs!
            (fn [evt]
              (t/is (= ::dwe/clear-edition-mode (ptk/type evt))))
            (fn [err]
              (done)
              (js/console.error err)
              (t/do-report {:type :error :message "Stream error" :actual err}))
            (fn [_]
              (done)))))))

(t/deftest test-handle-interrupt-noop
  (t/async
    done
    (let [event    (dwcm/handle-interrupt)
          state    {}
          result   (ptk/watch event state (rx/empty))
          emitted? (atom false)]
      (->> result
           (rx/subs!
            (fn [_]
              (reset! emitted? true))
            (fn [err]
              (done)
              (js/console.error err)
              (t/do-report {:type :error :message "Stream error" :actual err}))
            (fn [_]
              (t/is (false? @emitted?) "should not emit any events")
              (done)))))))
