;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.import.modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.workspace.tokens.errors :as dwte]
   [app.main.data.workspace.tokens.import-export :as dwti]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.notifications.context-notification :refer [context-notification*]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.webapi :as wapi]
   [app.util.zip :as uz]
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

(mf/defc import-type-dropdown*
  {::mf/private true}
  [{:keys [options on-click text-render default]}]
  (let [initial-option (or (first (filter #(= (:value %) default) options))
                           (first options))
        selected-option* (mf/use-state initial-option)
        selected-option @selected-option*
        show-dropdown? (mf/use-state false)

        file-type-options (mf/use-memo
                           (mf/deps options)
                           #(mapv (fn [option]
                                    {:id (str (:value option))
                                     :label (:label option)
                                     :aria-label (:label option)})
                                  options))

        button-text (if text-render
                      (text-render selected-option)
                      (:label selected-option))

        toggle-dropdown
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (swap! show-dropdown? not)))

        close-dropdown
        (mf/use-fn #(reset! show-dropdown? false))

        handle-option-click
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (let [target (dom/get-current-target event)
                 option-id (dom/get-attribute target "id")
                 option (first (filter #(= (str (:value %)) option-id) options))]
             (close-dropdown)
             (when option
               (reset! selected-option* option)))))

        handle-main-click
        (mf/use-fn
         (mf/deps on-click selected-option)
         (fn [event]
           (dom/prevent-default event)
           (when (and selected-option on-click)
             (on-click (:value selected-option)))))]

    [:div {:class (stl/css :dropdown-btn-wrapper)}
     [:> button* {:variant "primary"
                  :type "button"
                  :class (stl/css :dropdown-btn)
                  :on-click handle-main-click}
      button-text]

     [:> button* {:variant "primary"
                  :type "button"
                  :class (stl/css :dropdown-trigger-btn)
                  :icon "arrow-down"
                  :on-click toggle-dropdown
                  :aria-label "Show options"}]

     [:& dropdown {:show @show-dropdown? :on-close close-dropdown}
      [:> options-dropdown* {:options file-type-options
                             :selected (str (:value selected-option))
                             :on-click handle-option-click
                             :set-ref (fn [_] nil)}]]]))

(defn- has-token-files?
  [file-paths]
  (and (seq file-paths)
       (some #(str/ends-with? % ".json") file-paths)))

(defn- validate-token-files
  [file-stream]
  (->> file-stream
       (rx/reduce (fn [acc [file-path file-text]]
                    (conj acc [file-path file-text]))
                  [])
       (rx/tap (fn [file-entries]
                 (let [file-paths (map first file-entries)]
                   (when-not (has-token-files? file-paths)
                     (throw (dwte/error-ex-info :error.import/no-token-files-found file-paths nil))))))
       (rx/mapcat identity)))

(mf/defc import-modal-body*
  {::mf/private true}
  []
  (let [file-input-ref (mf/use-ref)
        dir-input-ref (mf/use-ref)
        zip-input-ref (mf/use-ref)

        on-display-file-explorer
        (mf/use-fn #(dom/click (mf/ref-val file-input-ref)))

        on-display-dir-explorer
        (mf/use-fn #(dom/click (mf/ref-val dir-input-ref)))

        on-display-zip-explorer
        (mf/use-fn #(dom/click (mf/ref-val zip-input-ref)))

        handle-import-action
        (mf/use-fn
         (mf/deps on-display-file-explorer on-display-dir-explorer on-display-zip-explorer)
         (fn [val]
           (case val
             :file (on-display-file-explorer)
             :folder (on-display-dir-explorer)
             :zip (on-display-zip-explorer)
             nil)))

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
                  (validate-token-files)
                  (dwti/import-directory-stream)
                  (on-stream-imported "multiple"))

             (-> (mf/ref-val dir-input-ref)
                 (dom/set-value! "")))))

        on-import-zip-file
        (mf/use-fn
         (fn [event]
           (let [zipfile (-> (dom/get-target event)
                             (dom/get-files)
                             (first))
                 zipfile-name (str/strip-suffix (.-name zipfile) ".zip")]
             (->> (wapi/read-file-as-array-buffer zipfile)
                  (rx/mapcat
                   (fn [file-content]
                     (let [zip-reader (uz/reader file-content)]
                       (->> (rx/from (uz/get-entries zip-reader))
                            (rx/mapcat
                             (fn [entries]
                               (->> (rx/from entries)
                                    (rx/filter (fn [entry]
                                                 (let [filename (.-filename entry)]
                                                   (str/ends-with? filename ".json"))))
                                    (rx/merge-map (fn [entry]
                                                    (let [filename (str/concat zipfile-name "/" (.-filename entry))
                                                          content-promise (uz/read-as-text entry)]
                                                      (-> content-promise
                                                          (.then (fn [text]
                                                                   [filename text]))
                                                          (rx/from))))))))
                            (rx/finalize (partial uz/close zip-reader))))))
                  (validate-token-files)
                  (dwti/import-directory-stream)
                  (on-stream-imported "zip"))

             (-> (mf/ref-val zip-input-ref)
                 (dom/set-value! "")))))

        on-import-json-file
        (mf/use-fn
         (fn [event]
           (let [file (-> (dom/get-target event)
                          (dom/get-files)
                          (first))]
             (->> (wapi/read-file-as-text file)
                  (dwti/import-file-stream (.-name file))
                  (on-stream-imported "single"))

             (-> (mf/ref-val file-input-ref)
                 (dom/set-value! "")))))

        render-button-text
        (mf/use-fn
         (fn [option]
           (tr "workspace.tokens.import-button-prefix" (:label option))))]

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
               :on-change on-import-json-file}]
      [:input {:type "file"
               :ref zip-input-ref
               :style {:display "none"}
               :accept ".zip"
               :on-change on-import-zip-file}]
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
      [:> import-type-dropdown*
       {:options [{:label (tr "workspace.tokens.import-menu-zip-option") :value :zip}
                  {:label (tr "workspace.tokens.import-menu-json-option") :value :file}
                  {:label (tr "workspace.tokens.import-menu-folder-option") :value :folder}]
        :on-click handle-import-action
        :text-render render-button-text
        :default :zip}]]]))
