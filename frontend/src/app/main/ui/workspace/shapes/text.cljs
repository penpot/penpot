;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.text
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text :as text]
   [app.main.ui.workspace.shapes.debug :as wsd]
   [rumext.v2 :as mf]))

;; --- Text Wrapper for workspace
(mf/defc text-wrapper
  {::mf/wrap-props false}
  [{:keys [shape]}]
  (let [shape-id (dm/get-prop shape :id)

        text-modifier-ref
        (mf/with-memo [shape-id]
          (refs/workspace-text-modifier-by-id shape-id))

        text-modifier
        (mf/deref text-modifier-ref)

        shape (if (some? shape)
                (dwt/apply-text-modifier shape text-modifier)
                shape)]

    [:> shape-container {:shape shape}
     [:g.text-shape {:key (dm/str shape-id)}
      [:& text/text-shape {:shape shape}]]

     (when *assert*
       [:& wsd/shape-debug {:shape shape}])]))
