;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.shapes.path
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.path :as types.path]
   [app.main.refs :as refs]
   [app.main.ui.shapes.path :as path]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.workspace.shapes.debug :as wsd]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- make-content-modifiers-ref
  [id]
  (l/derived (fn [local]
               (dm/get-in local [:edit-path id :content-modifiers]))
             refs/workspace-local))

(defn- apply-content-modifiers
  [shape content-modifiers]
  (let [shape (update shape :content types.path/apply-content-modifiers content-modifiers)]
    (types.path/update-geometry shape)))

(mf/defc path-wrapper
  {::mf/wrap-props false}
  [{:keys [shape]}]
  (let [shape-id (dm/get-prop shape :id)

        content-modifiers-ref
        (mf/with-memo [shape-id]
          (make-content-modifiers-ref shape-id))

        content-modifiers
        (mf/deref content-modifiers-ref)

        ;; FIXME: this should be provided by react context instead of using refs
        editing-id
        (mf/deref refs/selected-edition)

        editing?
        (= editing-id shape-id)

        shape
        (mf/with-memo [shape content-modifiers]
          (cond-> shape
            (some? content-modifiers)
            (apply-content-modifiers content-modifiers)))]

    [:> shape-container {:shape shape
                         :pointer-events (when editing? "none")}
     [:& path/path-shape {:shape shape}]
     (when *assert*
       [:& wsd/shape-debug {:shape shape}])]))
