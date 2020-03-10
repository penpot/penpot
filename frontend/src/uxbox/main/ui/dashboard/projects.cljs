;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2017 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.dashboard.projects
  (:refer-clojure :exclude [sort-by])
  (:require
   [cuerdas.core :as str]
   [lentes.core :as l]
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.constants :as c]
   [uxbox.main.data.projects :as udp]
   [uxbox.main.data.dashboard :as dsh]
   [uxbox.main.store :as st]
   [uxbox.main.exports :as exports]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.keyboard :as kbd]
   [uxbox.main.ui.confirm :refer [confirm-dialog]]
   [uxbox.main.ui.dashboard.common :as common]
   [uxbox.util.data :refer [read-string]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :as i18n :refer [t tr]]
   [uxbox.util.router :as rt]
   [uxbox.util.time :as dt]))

;; --- Helpers & Constants

(def +ordering-options+
  {:name "ds.ordering.by-name"
   :modified "ds.ordering.by-last-update"
   :created "ds.ordering.by-creation-date"})

;; --- Refs

(def opts-iref
  (-> (l/key :dashboard-projects)
      (l/derive st/state)))

(def projects-iref
  (-> (l/key :projects)
      (l/derive st/state)))

;; --- Helpers

(defn sort-by
  [ordering files]
  (case ordering
    :name (cljs.core/sort-by :name files)
    :created (reverse (cljs.core/sort-by :created-at files))
    :modified (reverse (cljs.core/sort-by :modified-at files))
    files))

(defn contains-term?
  [phrase term]
  (let [term (name term)]
    (str/includes? (str/lower phrase) (str/trim (str/lower term)))))

(defn filter-by
  [term files]
  (if (str/blank? term)
    files
    (filter #(contains-term? (:name %) term) files)))

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
  {:wrap [mf/wrap-memo]}
  [{:keys [file] :as props}]
  (let [local (mf/use-state {})
        on-navigate #(st/emit! (udp/go-to (:id file)))
        delete-fn #(st/emit! nil (udp/delete-file (:id file)))
        on-delete #(do
                     (dom/stop-propagation %)
                     (modal/show! confirm-dialog {:on-accept delete-fn}))

        on-blur #(let [name (-> % dom/get-target dom/get-value)]
                   (st/emit! (udp/rename-file (:id file) name))
                   (swap! local assoc :edition false))

        on-key-down #(when (kbd/enter? %) (on-blur %))
        on-edit #(do
                   (dom/stop-propagation %)
                   (dom/prevent-default %)
                   (swap! local assoc :edition true))]
    [:div.grid-item.project-th {:on-click on-navigate}
     [:& grid-item-thumbnail {:file file}]
     [:div.item-info
      (if (:edition @local)
        [:input.element-name {:type "text"
                              :auto-focus true
                              :on-key-down on-key-down
                              :on-blur on-blur
                              ;; :on-click on-edit
                              :default-value (:name file)}]
        [:h3 (:name file)])
      [:& grid-item-metadata {:modified-at (:modified-at file)}]]
     [:div.project-th-actions
      ;; [:div.project-th-icon.pages
      ;;  i/page
      ;;  #_[:span (:total-pages project)]]
      ;; [:div.project-th-icon.comments
      ;;  i/chat
      ;;  [:span "0"]]
      [:div.project-th-icon.edit
       {:on-click on-edit}
       i/pencil]
      [:div.project-th-icon.delete
       {:on-click on-delete}
       i/trash]]]))

;; --- Grid

(mf/defc grid
  [{:keys [id opts files] :as props}]
  (let [order (:order opts :modified)
        filter (:filter opts "")
        files (->> files
                   (filter-by filter)
                   (sort-by order))
        on-click #(do
                    (dom/prevent-default %)
                    (st/emit! dsh/create-file))]
    [:section.dashboard-grid
     [:div.dashboard-grid-content
      [:div.dashboard-grid-row
       [:div.grid-item.add-project {:on-click on-click}
        [:span (tr "ds.new-file")]]
       (for [item files]
         [:& grid-item {:file item :key (:id item)}])]]]))

;; --- Component: Nav

(mf/defc nav-item
  [{:keys [id name selected?] :as props}]
  (let [local (mf/use-state {:name name})
        editable? (not (nil? id))
        on-click #(st/emit! (udp/go-to-project id))
        on-dbl-click #(when editable? (swap! local assoc :edit true))
        on-input #(as-> % $
                    (dom/get-target $)
                    (dom/get-value $)
                    (swap! local assoc :name $))
        on-cancel #(swap! local assoc :edit false :name name)
        on-keyup #(cond
                    (kbd/esc? %)
                    (on-cancel)

                    (kbd/enter? %)
                    (let [name (-> % dom/get-target dom/get-value)]
                      (st/emit! (udp/rename-project id name))
                      (swap! local assoc :edit false)))]

    [:li {:on-click on-click
          :on-double-click on-dbl-click
          :class-name (when selected? "current")}
     (if (:edit @local)
       [:div
        [:input.element-title {:value (:name @local)
                               :on-change on-input
                               :on-key-down on-keyup}]
        [:span.close {:on-click on-cancel} i/close]]
       [:span.element-title name])]))

(mf/defc nav
  [{:keys [id] :as props}]
  (let [projects (->> (mf/deref projects-iref)
                      (vals)
                      (sort-by :created-at))]
    [:div.library-bar
     [:div.library-bar-inside
      [:form.dashboard-search
       [:input.input-text
        {:key :images-search-box
         :type "text"
         :auto-focus true
         :placeholder (tr "ds.search.placeholder")}]
       [:div.clear-search i/close]]
      [:ul.library-elements
       [:li.recent-projects #_{:on-click #(st/emit! (udp/go-to-project nil))
                               :class-name (when (nil? id) "current")}
        i/user
        [:span.element-title "Personal"]]

       [:li {:on-click #(st/emit! (udp/go-to-project nil))
                             :class-name (when (nil? id) "current")}
        i/file-html
        [:span.element-title "Drafts"]]

       [:li {:on-click #(st/emit! (udp/go-to-project nil))
                             :class-name (when (nil? id) "current")}
        i/icon-set
        [:span.element-title "Libraries"]]

       [:div.projects-row
        [:span "PROJECTS"]
        [:a.add-project {:on-click #(st/emit! udp/create-project)}
         i/close]]

       #_(for [item projects]
         [:& nav-item {:id (:id item)
                       :key (:id item)
                       :name (:name item)
                       :selected? (= (:id item) id)}])]]]))

;; --- Component: Content

(def files-ref
  (-> (comp (l/key :files)
            (l/lens vals))
      (l/derive st/state)))

(mf/defc content
  [{:keys [id] :as props}]
  (let [opts (mf/deref opts-iref)
        files (mf/deref files-ref)]
    [:section.dashboard-grid.library
     [:& grid {:id id :opts opts :files files}]]))

;; --- Projects Page

(mf/defc projects-page
  [{:keys [id] :as props}]
  (mf/use-effect {:fn #(st/emit! dsh/initialize-drafts)
                  :deps #js [id]})
  [:section.dashboard-content
   [:& nav {:id id}]
   [:& content {:id id}]])
