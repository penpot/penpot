(ns uxbox.main.ui.dashboard.grid
  (:refer-clojure :exclude [sort-by])
  (:require
   [cuerdas.core :as str]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.dashboard :as dsh]
   [uxbox.main.store :as st]
   [uxbox.main.exports :as exports]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.components.context-menu :refer [context-menu]]
   [uxbox.util.data :refer [classnames]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]))

;; --- Grid Item Thumbnail

(mf/defc grid-item-thumbnail
  [{:keys [file] :as props}]
  [:div.grid-item-th
   [:& exports/page-svg {:data (:data file)
                         :width "290"
                         :height "150"}]])

;; --- Grid Item

(mf/defc grid-item-metadata
  [{:keys [modified-at]}]
  (let [locale (i18n/use-locale)
        time (dt/timeago modified-at {:locale locale})]
    (str (t locale "ds.updated-at" time))))

(mf/defc grid-item
  {:wrap [mf/memo]}
  [{:keys [file] :as props}]
  (let [local (mf/use-state {:menu-open false
                             :edition false})
        locale (i18n/use-locale)
        on-navigate #(st/emit! (rt/nav :workspace
                                       {:project-id (:project-id file)
                                        :file-id (:id file)}
                                       {:page-id (first (:pages file))}))
        delete-fn #(st/emit! nil (dsh/delete-file (:id file)))
        on-delete #(do
                     (dom/stop-propagation %)
                     (modal/show! confirm-dialog {:on-accept delete-fn}))

        on-blur #(let [name (-> % dom/get-target dom/get-value)]
                   (st/emit! (dsh/rename-file (:id file) name))
                   (swap! local assoc :edition false))

        on-key-down #(cond
                       (kbd/enter? %) (on-blur %)
                       (kbd/esc? %) (swap! local assoc :edition false))
        on-menu-click #(do
                         (dom/stop-propagation %)
                         (swap! local assoc :menu-open true))
        on-menu-close #(swap! local assoc :menu-open false)
        on-edit #(do
                   (dom/stop-propagation %)
                   (swap! local assoc :edition true))]
    [:div.grid-item.project-th {:on-click on-navigate}
     [:div.overlay]
     [:& grid-item-thumbnail {:file file}]
     [:div.item-info
      (if (:edition @local)
        [:input.element-name {:type "text"
                              :auto-focus true
                              :on-key-down on-key-down
                              :on-blur on-blur
                              :default-value (:name file)}]
        [:h3 (:name file)])
      [:& grid-item-metadata {:modified-at (:modified-at file)}]]
     [:div.project-th-actions {:class (classnames
                                       :force-display (:menu-open @local))}
      ;; [:div.project-th-icon.pages
      ;;  i/page
      ;;  #_[:span (:total-pages project)]]
      ;; [:div.project-th-icon.comments
      ;;  i/chat
      ;;  [:span "0"]]
      [:div.project-th-icon.menu
       {:on-click on-menu-click}
       i/actions]
      [:& context-menu {:on-close on-menu-close
                        :show (:menu-open @local)
                        :options [[(t locale "dashboard.grid.edit") on-edit]
                                  [(t locale "dashboard.grid.delete") on-delete]]}]]]))

;; --- Grid

(mf/defc grid
  [{:keys [id opts files hide-new?] :as props}]
  (let [locale (i18n/use-locale)
        order (:order opts :modified)
        filter (:filter opts "")
        on-click #(do
                    (dom/prevent-default %)
                    (st/emit! (dsh/create-file id)))]
    [:section.dashboard-grid
     [:div.dashboard-grid-content
      (if (> (count files) 0)
        [:div.dashboard-grid-row
         (when (not hide-new?)
           [:div.grid-item.add-file {:on-click on-click}
            [:span (tr "ds.new-file")]])
         (for [item files]
           [:& grid-item {:file item :key (:id item)}])]
        [:div.grid-files-empty
         [:div.grid-files-desc (t locale "dashboard.grid.empty-files")]
         [:div.grid-files-link
          [:a.grid-files-link-text {:on-click on-click} (t locale "ds.new-file")]]])]]))
