(ns app.main.ui.workspace.tokens.sets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))

;; Sample data
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


(mf/defc sets-tree
  [{:keys [set-id]}]
  (println "Rendering set with ID:" set-id)
  (let [set (get sets set-id)]
    (when set
      (let [{:keys [type name children]} set
            icon (if (= type :group) i/document i/document)]
        [:div {:class (stl/css-case :set-item true :group (= type :group))}
         [:div {:class (stl/css :set-icon)}
          [:svg {:class (stl/css :set-icon-svg)} icon]]
         [:span {:class (stl/css :set-name)} name]
         (when children
           [:div {:class (stl/css :set-children)}
            (for [child-id children]
              (do
                (println "Rendering child ID:" child-id)
                ^{:key (str child-id)} [:& sets-tree {:key (str child-id) :set-id child-id}]))])]))))

(mf/defc sets-list
  {::mf/wrap-props false}
  []
  (println "Rendering Sets List with root order:" sets-root-order)
  [:div {:class (stl/css :sets-list)}
   (for [set-id sets-root-order]
     ^{:key (str set-id)}
     [:& sets-tree {:key (str set-id) :set-id set-id}])])

(mf/defc sets-sidebar
  {::mf/wrap-props false}
  []
  (println "Rendering sets sidebar")
  [:div {:class (stl/css :sets-sidebar)}
   [:div {:class (stl/css :sidebar-header)}
    "SETS"
    [:button {:class (stl/css :add-set)
              :on-click #(println "Add Set")} 
     i/add]]
   [:& sets-list]])
