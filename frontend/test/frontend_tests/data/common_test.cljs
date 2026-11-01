;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.common-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.common :as dc]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as hva]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARE LINK PAGE NAMES
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private page-home (uuid/custom 1 1))
(def ^:private page-flows (uuid/custom 1 2))
(def ^:private page-removed (uuid/custom 1 3))

(def ^:private file
  {:data {:pages [page-home page-flows]
          :pages-index {page-home {:id page-home :name "Home"}
                        page-flows {:id page-flows :name "Flows"}}}})

(t/deftest link-page-names-follow-the-file-page-order
  ;; The set of shared pages is unordered; the names must not be.
  (t/is (= "Home, Flows"
           (dc/get-link-page-names file #{page-flows page-home}))))

(t/deftest link-page-names-ignore-pages-that-no-longer-exist
  ;; A link can outlive the pages it was created for.
  (t/is (= "Flows"
           (dc/get-link-page-names file #{page-flows page-removed}))))

(t/deftest link-page-names-is-empty-when-no-page-survives
  (t/is (= ""
           (dc/get-link-page-names file #{page-removed}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SHARE LINK DELETION
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(t/deftest share-link-deleted-removes-only-the-target
  (let [link-a {:id (uuid/custom 1 4) :who-comment "team"}
        link-b {:id (uuid/custom 1 5) :who-comment "all"}
        state  {:share-links [link-a link-b]}
        result (ptk/update (dc/share-link-deleted (:id link-a)) state)]
    (t/is (= [link-b] (:share-links result)))))

(t/deftest ^:async delete-share-link-removes-the-row-when-the-server-accepts
  (let [link  {:id (uuid/custom 1 6)}
        store (ptk/store {:state {:share-links [link]}})]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [_ _] (rx/of nil)))}

       (ptk/emit! store (dc/delete-share-link link))
       (await (hva/wait-for #(empty? (:share-links @store))
                            "the deleted link leaves the list"))
       (t/is (= [] (:share-links @store)))))))

(t/deftest ^:async delete-share-link-keeps-the-link-when-the-server-refuses
  ;; Only the creator or a team admin may destroy a link. A refusal must
  ;; leave the list untouched and tell the user: the window exists to show
  ;; what still exists.
  (let [link  {:id (uuid/custom 1 7)}
        store (ptk/store {:state {:share-links [link]}})]
    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [_ _]
                             (rx/throw (ex-info "forbidden"
                                                {:type :authorization
                                                 :code :not-share-link-owner}))))}

       (ptk/emit! store (dc/delete-share-link link))
       (await (hva/wait-for #(some? (:notification @store))
                            "the refusal is reported to the user"))
       (t/is (= [link] (:share-links @store)))
       (t/is (= :error (:level (:notification @store))))))))
