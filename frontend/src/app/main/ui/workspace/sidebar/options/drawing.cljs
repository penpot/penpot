
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.drawing
  (:require
   [app.main.ui.workspace.sidebar.options.drawing.frame :as frame]
   [rumext.v2 :as mf]))

(mf/defc drawing-options*
  {::mf/wrap [#(mf/throttle % 60)]
   ::mf/private true}
  [{:keys [drawing-state] :as props}]
  (case (:tool drawing-state)
    :frame
    [:> frame/options* {:drawing-state drawing-state}]

    nil))


