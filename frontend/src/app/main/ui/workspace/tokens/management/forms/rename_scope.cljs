;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.tokens.management.forms.rename-scope
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.controls.radio-buttons :refer [radio-buttons*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc rename-scope*
  "Lets the user pick the sets a rename reaches: every set, the active
  ones or only the set of the renamed tokens. The new names are checked
  against those sets and their references follow there."
  [{:keys [scope on-change]}]
  (let [on-change (mf/use-fn
                   (mf/deps on-change)
                   (fn [value _]
                     (on-change (keyword value))))]
    [:div {:class (stl/css :rename-scope)}
     [:> text* {:as "span" :typography t/body-small :class (stl/css :label)}
      (tr "workspace.tokens.rename-scope")]
     [:> radio-buttons* {:name "rename-scope"
                         :aria-label (tr "workspace.tokens.rename-scope")
                         :selected scope
                         :extended true
                         :on-change on-change
                         :options [{:id "rename-scope-all"
                                    :label (tr "workspace.tokens.rename-scope-all")
                                    :value "all"}
                                   {:id "rename-scope-active"
                                    :label (tr "workspace.tokens.rename-scope-active")
                                    :value "active"}
                                   {:id "rename-scope-set"
                                    :label (tr "workspace.tokens.rename-scope-set")
                                    :value "set"}]}]]))
