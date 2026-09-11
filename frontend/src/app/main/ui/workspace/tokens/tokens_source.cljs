;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.tokens.tokens-source
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc tokens-source-info*
  {::mf/private true}
  [{:keys [tokens-source file-id] :as props}]

  (let [files              (mf/deref refs/files)
        tokens-source-file (get files tokens-source)
        source-file-id     (:id tokens-source-file)
        file-name          (:name tokens-source-file)

        file-name-ref          (mf/use-ref nil)
        file-name-truncated?*  (mf/use-state false)
        file-name-truncated?   (deref file-name-truncated?*)

        check-file-name-truncated
        (mf/use-fn
         (fn []
           (when-let [node (mf/ref-val file-name-ref)]
             (reset! file-name-truncated?*
                     (> (.-scrollWidth node) (.-clientWidth node))))))

        team-id
        (mf/use-ctx ctx/current-team-id)

        open-library-new-window
        (mf/use-fn
         (fn []
           (st/emit! (rt/nav :workspace
                             {:team-id team-id
                              :file-id tokens-source
                              :layout :tokens}
                             ::rt/new-window true))))
        show-libraries-dialog
        (mf/use-fn
         (mf/deps file-id)
         (fn []
           (modal/show! :libraries-dialog {:file-id file-id})))]

    (mf/with-effect [file-name]
      (check-file-name-truncated)
      (when-let [node (mf/ref-val file-name-ref)]
        (let [ro (js/ResizeObserver. check-file-name-truncated)]
          (.observe ro node)
          #(.disconnect ro))))

    [:div {:class (stl/css :tokens-source-wrapper)}
     [:div {:class (stl/css :tokens-source-header)}
      [:> icon* {:icon-id "tokens"
                 :size "m"
                 :class (stl/css :tokens-source-icon)}]
      [:> text* {:as "span"
                 :typography "body-small"
                 :class (stl/css :tokens-source-text)}
       (tr "workspace.tokens.source")]
      (if (= source-file-id file-id)
        [:> text* {:as "span"
                   :typography "body-small"
                   :class (stl/css :this-file)}
         (tr "workspace.tokens.this-file")]
        (if file-name-truncated?
          [:> tooltip* {:content file-name
                        :trigger-ref file-name-ref
                        :class (stl/css :file-name-tooltip)}
           [:> text* {:as "span"
                      :typography "body-small"
                      :class (stl/css :file-name)
                      :ref file-name-ref}
            file-name]]
          [:> text* {:as "span"
                     :typography "body-small"
                     :class (stl/css :file-name)
                     :ref file-name-ref}
           file-name]))]
     [:> icon-button*
      {:variant "ghost"
       :aria-label (tr "workspace.tokens.change-token-source")
       :on-click show-libraries-dialog
       :icon "switch"}]
     (when-not (= source-file-id file-id)
       [:> icon-button*
        {:variant "ghost"
         :aria-label (tr "workspace.tokens.open-source-new-tab")
         :on-click open-library-new-window
         :icon "open-link"}])]))
