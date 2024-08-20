;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [okulary.core :as l]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(def active-sets #{#uuid "2858b330-828e-4131-86ed-e4d1c0f4b3e3"
                   #uuid "d608877b-842a-473b-83ca-b5f8305caf83"})

(def sets-root-order [#uuid "2858b330-828e-4131-86ed-e4d1c0f4b3e3"
                      #uuid "9c5108aa-bdb4-409c-a3c8-c3dfce2f8bf8"
                      #uuid "0381446e-1f1d-423f-912c-ab577d61b79b"])

(def sets {#uuid "9c5108aa-bdb4-409c-a3c8-c3dfce2f8bf8" {:type :group
                                                         :name "Group A"
                                                         :children [#uuid "d1754e56-3510-493f-8287-5ef3417d4141"
                                                                    #uuid "d608877b-842a-473b-83ca-b5f8305caf83"]}
           #uuid "d608877b-842a-473b-83ca-b5f8305caf83" {:type :set
                                                         :name "Set A / 1"}
           #uuid "d1754e56-3510-493f-8287-5ef3417d4141" {:type :group
                                                         :name "Group A / B"
                                                         :children [#uuid "f608877b-842a-473b-83ca-b5f8305caf83"
                                                                    #uuid "7cc05389-9391-426e-bc0e-ba5cb8f425eb"]}
           #uuid "f608877b-842a-473b-83ca-b5f8305caf83" {:type :set
                                                         :name "Set A / B / 1"}
           #uuid "7cc05389-9391-426e-bc0e-ba5cb8f425eb" {:type :set
                                                         :name "Set A / B / 2"}
           #uuid "2858b330-828e-4131-86ed-e4d1c0f4b3e3" {:type :set
                                                         :name "Set Root 1"}
           #uuid "0381446e-1f1d-423f-912c-ab577d61b79b" {:type :set
                                                         :name "Set Root 2"}})

(def ^:private chevron-icon
  (i/icon-xref :arrow (stl/css :chevron-icon)))

(defn set-selected-set
  [set-id]
  (dm/assert! (uuid? set-id))
  (ptk/reify ::set-selected-set
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :selected-set-id set-id))))

(mf/defc sets-tree
  [{:keys [selected-set-id set-id]}]
  (let [set (get sets set-id)]
    (when set
      (let [{:keys [type name children]} set
            visible? (mf/use-state (contains? active-sets set-id))
            collapsed? (mf/use-state false)
            icon (if (= type :set) i/document i/group)
            selected? (mf/use-state (= set-id selected-set-id))

            on-click
            (mf/use-fn
             (mf/deps type set-id)
             (fn [event]
               (dom/stop-propagation event)
               (st/emit! (set-selected-set set-id))))]
        [:div {:class (stl/css :set-item-container)
               :on-click on-click}
         [:div {:class (stl/css-case :set-item-group (= type :group)
                                     :set-item-set  (= type :set)
                                     :selected-set  (and (= type :set) @selected?))}
          (when (= type :group)
            [:span {:class (stl/css-case
                            :collapsabled-icon true
                            :collapsed @collapsed?)
                    :on-click #(when (= type :group) (swap! collapsed? not))}
             chevron-icon])
          [:span {:class (stl/css :icon)} icon]
          [:div {:class (stl/css :set-name)} name]
          (when (= type :set)
            [:span {:class (stl/css :action-btn)
                    :on-click #(swap! visible? not)}
             (if @visible?
               i/shown
               i/hide)])]
         (when (and children (not @collapsed?))
           [:div {:class (stl/css :set-children)}
            (for [child-id children]
                [:& sets-tree {:key child-id :set-id child-id :selected-set-id selected-set-id}])])]))))

(mf/defc sets-list
  [{:keys [selected-set-id]}]
    [:ul {:class (stl/css :sets-list)}
     (for [set-id sets-root-order]
	       [:& sets-tree {:key set-id
	                      :set-id set-id
	                      :selected-set-id selected-set-id}])])
