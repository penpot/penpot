;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns common-tests.types.modifiers-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.types.modifiers :as ctm]
   [clojure.test :as t]))

(t/deftest modifiers->transform
  (let [modifiers
        (-> (ctm/empty)
            (ctm/move (gpt/point 100 200))
            (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
            (ctm/move (gpt/point -100 -200))
            (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5))
            (ctm/rotation (gpt/point 0 0) -100)
            (ctm/resize (gpt/point 100 200) (gpt/point 2.0 0.5)))

        transform (ctm/modifiers->transform modifiers)]

    (t/is (not (gmt/close? (gmt/matrix) transform)))))
