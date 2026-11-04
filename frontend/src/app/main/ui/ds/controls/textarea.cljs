;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.ds.controls.textarea
  (:require-macros
   [app.main.style :as stl])
  (:require
   [rumext.v2 :as mf]))

(def ^:private schema:textarea
  [:map
   [:class {:optional true} [:maybe :string]]])

(mf/defc textarea*
  "A text area of several lines. It takes the props of a `textarea`; name
  it with `aria-label` or a `label`."
  {::mf/schema schema:textarea}
  [{:keys [class] :rest props}]
  (let [props (mf/spread-props props {:class [class (stl/css :textarea)]})]
    [:> :textarea props]))
