;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.exports.files
  "The files export dialog/modal"
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.exports.files :as fexp]
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i :refer [icon*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.loader :refer [loader*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer  [tr]]
   [beicon.v2.core :as rx]
   [rumext.v2 :as mf]))

(defn- mark-file-error
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-error? true
                  :loading false))
        files))

(defn- mark-file-success
  [files file-id]
  (mapv #(cond-> %
           (= file-id (:id %))
           (assoc :export-success? true
                  :loading false))
        files))

(defn- initialize-state
  "Initialize export dialog state"
  [files]
  (let [files (mapv (fn [file] (assoc file :loading true)) files)]
    {:status :prepare
     :selected :all
     :files files}))

(mf/defc export-entry*
  {::mf/private true}
  [{:keys [file]}]
  [:div {:class (stl/css-case
                 :file-entry true
                 :loading  (:loading file)
                 :success  (:export-success? file)
                 :error    (:export-error? file))}

   [:div {:class (stl/css :file-name)}
    (if (:loading file)
      [:> loader*  {:width 26
                    :title (tr "labels.loading")}]
      (cond (:export-success? file)
            [:> icon* {:icon-id i/tick
                       :class (stl/css :file-icon)
                       :size "s"}]
            (:export-error? file)
            [:> icon* {:icon-id i/close
                       :class (stl/css :file-icon)
                       :size "s"}]))

    [:> text* {:class (stl/css :file-name-label)
               :as "span"
               :typography t/body-large}
     (:name file)]]])

(mf/defc export-dialog
  {::mf/register modal/components
   ::mf/register-as ::fexp/export-files
   ::mf/props :obj}
  [{:keys [team-id files]}]
  (let [state*       (mf/use-state (partial initialize-state files))
        has-libs?    (some :has-libraries files)

        state        (deref state*)
        selected     (:selected state)
        status       (:status state)

        start-export
        (mf/use-fn
         (mf/deps team-id selected files)
         (fn []
           (swap! state* assoc :status :exporting)
           (->> (fexp/export-files :files files :type selected)
                (rx/subs!
                 (fn [{:keys [file-id error filename uri] :as result}]
                   (if error
                     (swap! state* update :files mark-file-error file-id)
                     (do
                       (swap! state* update :files mark-file-success file-id)
                       (dom/trigger-download-uri filename "application/penpot" uri))))))))

        on-cancel
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (st/emit! (modal/hide))))

        on-accept
        (mf/use-fn
         (mf/deps start-export)
         (fn [event]
           (dom/prevent-default event)
           (start-export)))

        on-change
        (mf/use-fn
         (fn [event]
           (let [type (-> (dom/get-target event)
                          (dom/get-data "type")
                          (keyword))]
             (swap! state* assoc :selected type))))]

    (mf/with-effect [has-libs?]
      ;; Start download automatically when no libraries
      (when-not has-libs?
        (start-export)))

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:> heading* {:level 2
                     :typography t/headline-large
                     :class (stl/css :modal-title)}
        (tr "files-download-modal.title")]
       [:> icon-button* {:variant "ghost"
                         :aria-label (tr "labels.close")
                         :on-click on-cancel
                         :class (stl/css :modal-close-btn)
                         :icon i/close}]]
      (cond
        (= status :prepare)
        [:*
         [:div {:class (stl/css :modal-content)}
          ;; TODO: Add translation
          [:> text* {:as "p" :typography t/body-large :class (stl/css :modal-msg)}
           "What do you want to do with linked libraries?"]

          (for [type fexp/valid-types]
            [:div {:class (stl/css :export-option true)
                   :key (name type)}
             [:label {:for (str "export-" type)
                      :class (stl/css :export-option-label)}
              ;; Execution time translation strings:
              ;;   (tr "files-export-modal.options.all.title")
              ;;   (tr "files-export-modal.options.all.message")

              ;;   (tr "files-export-modal.options.merge.title")
              ;;   (tr "files-export-modal.options.merge.message")

              ;;   (tr "files-export-modal.options.detach.title")
              ;;   (tr "files-export-modal.options.detach.message")

              ;;   (tr "files-export-modal.options.link-later.title")
              ;;   (tr "files-export-modal.options.link-later.message")

              [:span {:class (stl/css-case
                              :option-icon-wrapper true
                              :checked (= selected type))}
               (when (= selected type)
                 [:svg {:class (stl/css :option-icon)
                        :viewBox "0 0 8 8"
                        :width 8
                        :height 8
                        :aria-hidden true}
                  [:circle {:cx 4 :cy 4 :r 4}]])]

              [:div {:class (stl/css :option-content)}
               [:> heading* {:level 3
                             :typography t/body-large
                             :class (stl/css :option-title)}
                (tr (dm/str "files-export-modal.options." (d/name type) ".title"))]
               [:> text* {:as "p" :typography t/body-large :class (stl/css :modal-msg)}
                (tr (dm/str "files-export-modal.options." (d/name type) ".message"))]]

              [:input {:type "radio"
                       :class (stl/css :option-input)
                       :id (str "export-" type)
                       :checked (= selected type)
                       :name "export-option"
                       :data-type (name type)
                       :on-change on-change}]]])]

         [:div {:class (stl/css :modal-footer)}
          [:div {:class (stl/css :action-buttons)}
           [:> button* {:variant "secondary"
                        :type "button"
                        :on-click on-cancel}
            (tr "labels.cancel")]

           [:> button* {:variant "primary"
                        :type "button"
                        :on-click on-accept}
            (tr "labels.continue")]]]]

        (= status :exporting)
        (let [in-progress? (->> state :files (some :loading))]
          [:*
           [:div {:class (stl/css :modal-content)}
            (for [file (:files state)]
              [:> export-entry* {:file file :key (dm/str (:id file))}])

            (when in-progress?
              [:> text* {:as "span" :typography t/body-large :class (stl/css :status-message)
                         :role "status"
                         :aria-live "polite"}
               (tr "labels.downloading-file")])]

           [:div {:class (stl/css :modal-footer)}
            [:div {:class (stl/css :action-buttons)}
             [:> button* {:variant "primary"
                          :type "button"
                          :disabled in-progress?
                          :on-click on-cancel}
              (tr "labels.close")]]]]))]]))
