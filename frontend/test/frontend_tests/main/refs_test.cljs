;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.main.refs-test
  (:require
   [app.common.uuid :as uuid]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]))

(def ^:private resolved-uri? @#'refs/resolved-uri?)

(t/deftest resolved-uri-detects-blob-and-data-uris
  (t/is (true? (resolved-uri? "blob:http://localhost/thumb")))
  (t/is (true? (resolved-uri? "data:image/png;base64,iVBOR"))))

(t/deftest resolved-uri-rejects-plain-media-ids
  (t/is (false? (resolved-uri? "275c9c60-7f5e-4a2b-9f1c-2d3e4f5a6b7c")))
  (t/is (false? (resolved-uri? ""))))

(t/deftest resolved-uri-tolerates-non-string-uris
  ;; Thumbnail :uri values arrive from the server over transit, so
  ;; media-ids decode to UUID objects instead of strings. These must
  ;; fall through to resolve-media instead of raising a TypeError
  ;; (str.lastIndexOf is not a function) that crashes the workspace.
  (t/is (false? (resolved-uri? (uuid/next))))
  (t/is (false? (resolved-uri? nil)))
  (t/is (false? (resolved-uri? 42))))

(t/deftest thumbnail-ref-ignores-unrelated-state-changes
  ;; A thumbnail loaded from the server keeps a media id as :uri, and
  ;; the ref resolves it to a new map on each read. Subscribers (root
  ;; frames, assets) must not re-render on every unrelated store change.
  (let [object-id (str (uuid/next))
        ref       (refs/workspace-thumbnail-by-id object-id)
        calls     (atom 0)]
    (swap! st/state assoc-in [:thumbnails object-id] {:uri (uuid/next) :rendered-at nil})
    (add-watch ref ::probe (fn [_ _ _ _] (swap! calls inc)))
    (try
      (swap! st/state assoc ::probe (uuid/next))
      (t/is (zero? @calls) "unrelated change does not notify")
      (swap! st/state assoc-in [:thumbnails object-id :uri] (uuid/next))
      (t/is (= 1 @calls) "a new thumbnail uri notifies")
      (finally
        (remove-watch ref ::probe)
        (swap! st/state #(-> %
                             (dissoc ::probe)
                             (update :thumbnails dissoc object-id)))))))
