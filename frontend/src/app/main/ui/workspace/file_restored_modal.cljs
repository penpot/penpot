;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.file-restored-modal
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.store :as st]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

#_{:clojure-lsp/ignore [:clojure-lsp/unused-public-var]}
(mf/defc file-restored-modal*
  "Tells a peer that another user restored an earlier version of the open
  file, which was reloaded."
  {::mf/register modal/components
   ::mf/register-as :file-restored}
  [{:keys [fullname]}]
  (let [on-close (mf/use-fn #(st/emit! (modal/hide)))]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)
            :role "dialog"
            :aria-modal true
            :aria-labelledby "file-restored-title"}
      [:> heading* {:id "file-restored-title"
                    :level 2
                    :typography t/title-medium
                    :class (stl/css :modal-title)}
       (tr "workspace.file-restored.title")]
      [:> text* {:as "p" :typography t/body-large}
       (if fullname
         (tr "workspace.file-restored.message" fullname)
         (tr "workspace.file-restored.message-anonymous"))]
      [:footer {:class (stl/css :modal-footer)}
       [:> button* {:on-click on-close
                    :variant "primary"}
        (tr "ds.confirm-ok")]]]]))
