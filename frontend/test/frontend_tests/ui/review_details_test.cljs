;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.review-details-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.review :as dwrv]
   [app.main.ui.workspace.review.common :as rvc]
   [app.main.ui.workspace.review.details :as details]
   [cljs.test :as t :include-macros true]
   [potok.v2.core :as ptk]))

(def ^:private display-attr @#'details/display-attr)

(def ^:private activity-fetched @#'dwrv/activity-fetched)

(t/deftest a-fill-without-tokens-shows-its-value
  (let [fill-a [{:fill-color "#ff0000" :fill-opacity 1}]
        fill-b [{:fill-color "#00ff00" :fill-opacity 1}]
        [_ before after] (display-attr :fills [fill-a fill-b] nil)]
    (t/is (= fill-a before))
    (t/is (= fill-b after))))

(t/deftest a-token-on-one-side-shows-the-token
  (let [fill [{:fill-color "#ff0000" :fill-opacity 1}]
        [_ before after] (display-attr :fills [fill fill] [nil {:fill "brand.primary"}])]
    (t/is (= fill before))
    (t/is (= "brand.primary" after))))

(t/deftest a-property-that-takes-no-tokens-shows-its-value
  (let [[_ before after] (display-attr :opacity [0.5 1] [{:fill "a"} {:fill "b"}])]
    (t/is (= 0.5 before))
    (t/is (= 1 after))))

(t/deftest the-tasks-filter-keeps-one-state-or-all
  (let [state (ptk/update (dwrv/set-task-status-filter "verified") {})]
    (t/is (= "verified" (get-in state [:workspace-review-local :task-status-filter])))
    (t/is (= :all (-> (ptk/update (dwrv/set-task-status-filter :all) state)
                      (get-in [:workspace-review-local :task-status-filter]))))))

(t/deftest every-task-state-has-its-colour
  (t/is (= ["info" "success" "warning" "purple" "error"]
           (mapv rvc/status-tone dwrv/task-statuses))))

(t/deftest the-activity-comes-folded-from-the-server
  (let [entry     {:id (uuid/random) :page-id (uuid/random) :kind :added}
        session   {:profile-id (uuid/random)
                   :start (js/Date.)
                   :end (js/Date.)
                   :entries [entry]}
        last-save {:created-at (js/Date.) :profile-id (uuid/random)}
        pr-id     (uuid/random)
        state     (ptk/update (activity-fetched pr-id {:entries [entry]
                                                       :sessions [session]
                                                       :people 2
                                                       :last-save last-save})
                              {})
        activity  (:workspace-review-activity state)]
    (t/is (= :loaded (:status activity)))
    (t/is (= pr-id (:pr-id activity)))
    (t/is (= [entry] (:entries activity)))
    (t/is (= [session] (:sessions activity)))
    (t/is (= 2 (:people activity)))
    (t/is (= last-save (:last-save activity)))))
