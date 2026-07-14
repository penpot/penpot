;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.data.comments-filters-test
  (:require
   [app.main.data.comments :as dcmt]
   [app.util.storage :as storage]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private storage-key
  :app.main.data.comments/hide-resolved-comments?)

(t/deftest test-merge-persisted-filters-default
  (let [prev (get @storage/user storage-key)]
    (try
      (swap! storage/user dissoc storage-key)
      (t/is (= {:show :all} (dcmt/merge-persisted-filters nil)))
      (t/is (= {:show :all} (dcmt/merge-persisted-filters {})))
      (finally
        (if (some? prev)
          (swap! storage/user assoc storage-key prev)
          (swap! storage/user dissoc storage-key))))))

(t/deftest test-merge-persisted-filters-hide-resolved
  (let [prev (get @storage/user storage-key)]
    (try
      (swap! storage/user assoc storage-key true)
      (t/is (= {:show :pending} (dcmt/merge-persisted-filters nil)))
      (finally
        (if (some? prev)
          (swap! storage/user assoc storage-key prev)
          (swap! storage/user dissoc storage-key))))))

(t/deftest test-merge-persisted-filters-keeps-session-value
  (let [prev (get @storage/user storage-key)]
    (try
      (swap! storage/user assoc storage-key true)
      (t/is (= {:show :all :mode :yours}
               (dcmt/merge-persisted-filters {:show :all :mode :yours})))
      (finally
        (if (some? prev)
          (swap! storage/user assoc storage-key prev)
          (swap! storage/user dissoc storage-key))))))

(t/deftest test-update-filters-updates-show
  (let [event (dcmt/update-filters {:show :pending})
        state (ptk/update event {})]
    (t/is (= :pending (get-in state [:comments-local :show])))

    (let [event (dcmt/update-filters {:show :all})
          state (ptk/update event state)]
      (t/is (= :all (get-in state [:comments-local :show]))))))
