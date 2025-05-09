(ns app.main.ui.workspace.tokens.modals.import
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.main.data.event :as ev]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.errors :as wte]
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

(defn drop-parent-directory [path]
  (->> (cfh/split-path path)
       (rest)
       (str/join "/")))

(defn remove-path-extension [path]
  (-> (str/split path ".")
      (butlast)
      (str/join)))

(defn file-path->set-name
  [path]
  (-> path
      (drop-parent-directory)
      (remove-path-extension)))

(defn on-import-stream [tokens-lib-stream]
  (rx/sub!
   tokens-lib-stream
   (fn [lib]
     (st/emit! (ptk/data-event ::ev/event {::ev/name "import-tokens"})
               (dwtl/import-tokens-lib lib))
     (modal/hide!))
   (fn [err]
     (js/console.error err)
     (st/emit! (ntf/show {:content (wte/humanize-errors [(ex-data err)])
                          :detail (wte/detail-errors [(ex-data err)])
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
                            ;; Read files as text, ignore files with json parse errors
                            (map (fn [file]
                                   (->> (wapi/read-file-as-text file)
                                        (rx/mapcat (fn [json]
                                                     (let [path (.-webkitRelativePath file)]
                                                       (rx/of
                                                        (try
                                                          {(file-path->set-name path) (sd/parse-json json)}
                                                          (catch js/Error e
                                                            {:path path :error e}))))))))))]

             (->> (apply rx/merge files)
                  (rx/reduce (fn [acc cur]
                               (if (:error cur)
                                 acc
                                 (conj acc cur)))
                             {})
                  (rx/map #(sd/decode-json-data (if (= 1 (count %))
                                                  (val (first %))
                                                  %)
                                                (ffirst %)))
                  (on-import-stream))

             (-> (mf/ref-val dir-input-ref)
                 (dom/set-value! "")))))

        on-import
        (mf/use-fn
         (fn [event]
           (let [file (-> (dom/get-target event)
                          (dom/get-files)
                          (first))
                 file-name (remove-path-extension (.-name file))]
             (->> (wapi/read-file-as-text file)
                  (sd/process-json-stream {:file-name file-name})
                  (on-import-stream))

             (-> (mf/ref-val file-input-ref)
                 (dom/set-value! "")))))]

    [:div {:class (stl/css :import-modal-wrapper)}
     [:> heading* {:level 2 :typography "headline-medium" :class (stl/css :import-modal-title)}
      (tr "workspace.token.import-tokens")]

     [:> text* {:as "ul" :typography "body-medium" :class (stl/css :import-description)}
      [:li (tr "workspace.token.import-single-file")]
      [:li (tr "workspace.token.import-multiple-files")]]

     [:> context-notification* {:type :context
                                :appearance "neutral"
                                :level "default"
                                :is-html true}
      (tr "workspace.token.import-warning")]

     [:div {:class (stl/css :import-actions)}
      [:input {:type "file"
               :ref file-input-ref
               :style {:display "none"}
               :accept ".json"
               :on-change on-import}]
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
       (tr "workspace.token.choose-file")]
      [:> button* {:variant "primary"
                   :type "button"
                   :icon i/folder
                   :on-click on-display-dir-explorer}
       (tr "workspace.token.choose-folder")]]]))

(mf/defc import-modal
  {::mf/wrap-props false
   ::mf/register modal/components
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
