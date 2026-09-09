;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.plugins.user-test
  (:require
   [app.main.data.comments :as dc]
   [app.main.store :as st]
   [app.plugins.api :as api]
   [app.plugins.comments :as comments]
   [app.plugins.file :as file]
   [app.plugins.register :as r]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]))

(def ^:private plugin-id "00000000-0000-0000-0000-000000000000")

(t/deftest comment-thread-owner-returns-nil-without-user-read
  (let [owner-id  (random-uuid)
        file-id   (random-uuid)
        page-id   (random-uuid)
        thread-id (random-uuid)
        thread    (comments/comment-thread-proxy
                   plugin-id
                   file-id
                   page-id
                   {:id thread-id :owner-id owner-id})]
    (with-redefs [r/check-permission (constantly false)
                  dc/get-owner       (constantly {:id owner-id :fullname "Owner"})]
      (t/is (nil? (.-owner thread)))
      (t/is (nil? (.-user thread))))))

(t/deftest comment-reply-owner-returns-nil-without-user-read
  (let [owner-id  (random-uuid)
        file-id   (random-uuid)
        page-id   (random-uuid)
        thread-id (random-uuid)
        reply-id  (random-uuid)
        reply     (comments/comment-proxy
                   plugin-id
                   file-id
                   page-id
                   thread-id
                   {:id reply-id :owner-id owner-id})]
    (with-redefs [r/check-permission (constantly false)
                  dc/get-owner       (constantly {:id owner-id :fullname "Owner"})]
      (t/is (nil? (.-owner reply)))
      (t/is (nil? (.-user reply))))))

(t/deftest file-version-created-by-returns-nil-without-user-read
  (let [file-id    (random-uuid)
        version-id (random-uuid)
        profile-id (random-uuid)
        version    (file/file-version-proxy
                    plugin-id
                    file-id
                    {profile-id {:id profile-id :fullname "User"}}
                    {:id version-id
                     :label "Version"
                     :created-at (js/Date.)
                     :profile-id profile-id})]
    (with-redefs [r/check-permission (constantly false)]
      (t/is (nil? (.-createdBy version))))))

(t/deftest get-current-user-returns-nil-without-user-read
  (let [ctx (api/create-context plugin-id)]
    (with-redefs [r/check-permission (constantly false)
                  st/state           (atom {:session-id (random-uuid)
                                            :profile   {:id (random-uuid) :fullname "User"}})]
      (t/is (nil? (.getCurrentUser ctx))))))

(t/deftest get-active-users-returns-empty-without-user-read
  (let [ctx (api/create-context plugin-id)]
    (with-redefs [r/check-permission (constantly false)
                  st/state           (atom {:session-id        (random-uuid)
                                            :profile           {:id (random-uuid)}
                                            :workspace-presence {(random-uuid) {:id (random-uuid)}}})]
      (t/is (zero? (.-length (.getActiveUsers ctx)))))))
