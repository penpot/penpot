(ns uxbox.ui.workspace.pagesmngr
  (:require [sablono.core :as html :refer-macros [html]]
            [rum.core :as rum]
            [cuerdas.core :as str]
            [uxbox.rstore :as rs]
            [uxbox.data.projects :as dp]
            [uxbox.ui.icons :as i]
            [uxbox.ui.keyboard :as k]
            [uxbox.ui.mixins :as mx]
            [uxbox.ui.workspace.base :as wb]
            [uxbox.ui.dom :as dom]
            [uxbox.ui.util :as util]))

(defn- page-item-render
  [own parent page numpages]
  (letfn [(on-edit [e]
            (let [data {:edit true :form page}]
              (reset! parent data)))]

    (let [curpage (rum/react wb/page-state)
          active? (= (:id curpage) (:id page))
          deletable? (> numpages 1)
          navigate #(rs/emit! (dp/go-to-project (:project page) (:id page)))
          delete #(rs/emit! (dp/delete-page (:id page)))]
      (html
       [:li.single-page
        {:class (when active? "current")
         :key (str (:id page))
         :on-click navigate}
        [:div.tree-icon i/page]
        [:span (:name page)]
        [:div.options
         [:div {:on-click on-edit} i/pencil]
         [:div {:class (when-not deletable? "hide")
                :on-click delete}
          i/trash]]]))))

(def page-item
  (util/component
   {:render page-item-render
    :name "page-item"
    :mixins [rum/reactive]}))

(defn- page-list-render
  [own parent]
  (let [project (rum/react wb/project-state)
        pages (rum/react wb/pages-state)
        name (:name project)]
    (html
     [:div.project-bar-inside
      [:span.project-name name]
      [:ul.tree-view
       (for [page pages]
         (-> (page-item parent page (count pages))
             (rum/with-key (:id page))))]
      [:button.btn-primary.btn-small
       {:on-click #(reset! parent {:edit true :form {}})}
       "+ Add new page"]])))

(def page-list
  (util/component
   {:render page-list-render
    :name "page-list"
    :mixins [rum/reactive]}))

(defn- page-form-render
  [own parent]
  (let [form (:form @parent)
        project @wb/project-state]
    (letfn [(on-change [e]
              (let [value (dom/event->value e)]
                (swap! parent assoc-in [:form :name] value)))
            (persist []
              (if (nil? (:id form))
                (let [data {:project (:id project)
                            :width (:width project)
                            :height (:height project)
                            :name (:name form)}]
                  (rs/emit! (dp/create-page data))
                  (reset! parent {:edit false}))
                (do
                  (rs/emit! (dp/update-page-name (:id form) (:name form)))
                  (reset! parent {:edit false}))))
            (on-save [e]
              (persist))
            (on-key-up [e]
              (when (k/enter? e)
                (persist)))
            (on-cancel [e]
              (reset! parent {:edit false}))]
      (html
       [:div.project-bar-inside
        [:input.input-text
         {:name "test"
          :auto-focus true
          :placeholder "Page title"
          :type "text"
          :value (get-in @parent [:form :name] "")
          :on-change on-change
          :on-key-up on-key-up}]
        [:button.btn-primary.btn-small
         {:disabled (str/empty? (str/trim (get-in @parent [:form :name] "")))
          :on-click on-save}
         "Save"]
        [:button.btn-delete.btn-small
         {:on-click on-cancel}
         "Cancel"]]))))

(def page-form
  (util/component
   {:render page-form-render
    :name "page-form"
    :mixins [rum/reactive]}))

(defn pagesmngr-render
  [own]
  (let [local (:rum/local own)
        workspace (rum/react wb/workspace-state)
        project (rum/react wb/project-state)]
    (html
     [:div#project-bar.project-bar
      (when-not (:pagesbar-enabled workspace false)
        {:class "toggle"})
      (if (:edit @local)
        (page-form local)
        (page-list local))])))

(def pagesmngr
  (util/component
   {:render pagesmngr-render
    :name "pagesmngr"
    :mixins [rum/reactive (mx/local {:edit false :form {}})]}))
