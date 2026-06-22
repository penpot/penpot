;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.comments-test
  (:require
   [app.main.data.comments :as dc]
   [app.main.store :as st]
   [app.plugins.comments :as comments]
   [app.plugins.page :as page]
   [app.plugins.register :as r]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(t/deftest comment-thread-remove-allows-the-owner
  (let [owner-id  (random-uuid)
        file-id   (random-uuid)
        page-id   (random-uuid)
        thread-id (random-uuid)
        emitted   (atom nil)
        thread    (comments/comment-thread-proxy
                   plugin-id
                   file-id
                   page-id
                   {:id thread-id :owner-id owner-id})]
    (set! st/state (atom {:profile {:id owner-id}}))
    (with-redefs [r/check-permission (constantly true)
                  dc/delete-comment-thread-on-workspace
                  (mock/stub (fn [params callback]
                               (callback)
                               [:delete-thread params]))
                  st/emit! (mock/stub (fn [event] (reset! emitted event)))]
      (let [result (.remove thread)]
        (t/is (instance? js/Promise result))
        (t/is (= [:delete-thread {:id thread-id}] @emitted))))))
