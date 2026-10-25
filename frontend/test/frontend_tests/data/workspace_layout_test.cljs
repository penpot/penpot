;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-layout-test
  (:require
   [app.main.data.workspace.layout :as layout]
   [app.util.storage :as storage]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(t/deftest compact-mode-valid-flag-test
  (t/testing "compact-mode is registered in valid layout flags"
    (t/is (contains? layout/valid-flags :compact-mode))))

(t/deftest compact-mode-toggle-event-test
  (t/testing "toggle-layout-flag toggles :compact-mode"
    (let [event (layout/toggle-layout-flag :compact-mode)
          state-empty {:workspace-layout #{}}
          state-with  (ptk/update event state-empty)
          state-without (ptk/update event state-with)]
      (t/is (contains? (:workspace-layout state-with) :compact-mode))
      (t/is (not (contains? (:workspace-layout state-without) :compact-mode))))))

(t/deftest compact-mode-persistence-test
  (t/testing "compact-mode flag is persisted and loaded from storage"
    (let [storage-key :app.main.data.workspace/compact-mode?]
      (swap! storage/user assoc storage-key true)
      (t/is (contains? (layout/load-layout-flags #{}) :compact-mode))

      (swap! storage/user assoc storage-key false)
      (t/is (not (contains? (layout/load-layout-flags #{:compact-mode}) :compact-mode)))

      ;; Persistence test
      (layout/persist-layout-flags! #{:compact-mode})
      (t/is (true? (get @storage/user storage-key)))

      (layout/persist-layout-flags! #{})
      (t/is (false? (get @storage/user storage-key))))))
