;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.token-input
   (:require-macros
    [app.main.style :as stl])
   (:require
    [rumext.v2 :as mf]))

  (mf/defc token-input*
    [{:keys [] :rest props}]
    [:div {:class (stl/css-case
                   :wrapper true)}

     "token-input* not implemented yet"])
