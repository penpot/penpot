;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals.import
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.tokens.errors :as dwte]
   [app.main.data.workspace.tokens.import-export :as dwti]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(defn- on-stream-imported
  [type tokens-lib-stream]
  (rx/sub!
   tokens-lib-stream
   (fn [lib]
     (st/emit! (ptk/data-event ::ev/event {::ev/name "import-tokens" :type type})
               (dwtl/import-tokens-lib lib))
     (modal/hide!))
   (fn [err]
     (st/emit! (ntf/show {:content (dwte/humanize-errors [(ex-data err)])
                          :detail (dwte/detail-errors [(ex-data err)])
                          :type :toast
                          :level :error})))))

(mf/defc import-modal-body*
  {::mf/private true}
  []
  (let [file-input-ref (mf/use-ref)
        dir-input-ref (mf/use-ref)

        on-display-file-explorer
        (mf/use-fn #(dom/click (mf/ref-val file-input-ref)))

        on-display-dir-explorer
        (mf/use-fn #(dom/click (mf/ref-val dir-input-ref)))

        on-import-directory
        (mf/use-fn
         (fn [event]
           (let [files (->> (dom/get-target event)
                            (dom/get-files)
                            (filter (fn [file]
                                      (let [name (.-name file)
                                            type (.-type file)]
                                        (or
                                         (= type "application/json")
                                         (str/ends-with? name ".json"))))))]
             (->> (rx/from files)
                  (rx/mapcat (fn [file]
                               (->> (wapi/read-file-as-text file)
                                    (rx/map (fn [file-text]
                                              [(.-webkitRelativePath file)
                                               file-text])))))
                  (dwti/import-directory-stream)
                  (on-stream-imported "multiple"))

             (-> (mf/ref-val dir-input-ref)
                 (dom/set-value! "")))))

        on-import-file
        (mf/use-fn
         (fn [event]
           (let [file (-> (dom/get-target event)
                          (dom/get-files)
                          (first))]
             (->> (wapi/read-file-as-text file)
                  (dwti/import-file-stream (.-name file))
                  (on-stream-imported "single"))

             (-> (mf/ref-val file-input-ref)
                 (dom/set-value! "")))))]

    [:div {:class (stl/css :import-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :import-modal-title)}
      (tr "workspace.tokens.import-tokens")]

     [:> text* {:as "ul" :typography "body-medium" :class (stl/css :import-description)}
      [:li (tr "workspace.tokens.import-single-file")]
      [:li (tr "workspace.tokens.import-multiple-files")]]

     [:> context-notification* {:type :context
                                :appearance "neutral"
                                :level "default"
                                :is-html true}
      (tr "workspace.tokens.import-warning")]

     [:div {:class (stl/css :import-actions)}
      [:input {:type "file"
               :ref file-input-ref
               :style {:display "none"}
               :accept ".json"
               :on-change on-import-file}]
      [:input {:type "file"
               :ref dir-input-ref
               :style {:display "none"}
               :accept ""
               :webkitdirectory "true"
               :on-change on-import-directory}]
      [:> button* {:variant "secondary"
                   :type "button"
                   :on-click modal/hide!}
       (tr "labels.cancel")]
      [:> button* {:variant "primary"
                   :type "button"
                   :icon i/document
                   :on-click on-display-file-explorer}
       (tr "workspace.tokens.choose-file")]
      [:> button* {:variant "primary"
                   :type "button"
                   :icon i/folder
                   :on-click on-display-dir-explorer}
       (tr "workspace.tokens.choose-folder")]]]))

(mf/defc import-modal*
  {::mf/register modal/components
   ::mf/register-as :tokens/import}
  []
  [:div {:class (stl/css :modal-overlay)}
   [:div {:class (stl/css :modal-dialog)}
    [:> icon-button* {:class (stl/css :close-btn)
                      :on-click modal/hide!
                      :aria-label (tr "labels.close")
                      :variant "ghost"
                      :icon "close"}]
    [:> import-modal-body*]]])
