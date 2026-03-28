;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.workspace-thumbnails-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.thumbnails :as thumbnails]
   [cljs.test :as t :include-macros true]))

(t/deftest extract-frame-changes-handles-cyclic-frame-links
  (let [page-id    (uuid/next)
        root-id    (uuid/next)
        shape-a-id (uuid/next)
        shape-b-id (uuid/next)
        event      {:changes [{:type :mod-obj
                               :page-id page-id
                               :id shape-a-id}]}
        old-data   {:pages-index
                    {page-id
                     {:objects
                      {root-id    {:id root-id :type :frame :frame-id uuid/zero}
                       shape-a-id {:id shape-a-id :type :rect :frame-id shape-b-id}
                       shape-b-id {:id shape-b-id :type :group :frame-id shape-a-id}}}}}
        new-data   {:pages-index
                    {page-id
                     {:objects
                      {root-id    {:id root-id :type :frame :frame-id uuid/zero}
                       shape-a-id {:id shape-a-id :type :rect :frame-id root-id}
                       shape-b-id {:id shape-b-id :type :group :frame-id shape-a-id
                                   :component-root true}}}}}]
    (t/is (= #{["frame" root-id]
               ["component" shape-b-id]}
             (#'thumbnails/extract-frame-changes page-id [event [old-data new-data]])))))
