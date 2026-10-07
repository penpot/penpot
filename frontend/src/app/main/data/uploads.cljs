;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.data.uploads
  "Generic chunked-upload helpers.

  Provides a purpose-agnostic three-step session API that can be used
  by any feature that needs to upload large binary blobs:

    1. create-upload-session  – obtain a session-id
    2. upload-chunk           – upload each slice, one at a time
    3. caller-specific step   – e.g. assemble-file-media-object or import-binfile

  `upload-blob-chunked` drives steps 1 and 2 and emits the completed
  `{:session-id …}` map so that the caller can proceed with its own
  step 3."
  (:require
   [app.common.data.macros :as dm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]))

(defn upload-blob-chunked
  "Uploads `blob` via the three-step chunked session API.

  Steps performed:
    1. Creates an upload session  (`create-upload-session`).
    2. Slices `blob` and uploads every chunk  (`upload-chunk`), one at a
       time so the progress of the file is a straight line.

  Returns an observable that emits exactly one map:
    `{:session-id <uuid>}`

  The caller is responsible for the final step (assemble / import).

  The optional `opts` map accepts:
    `:chunk-size` – size in bytes of each chunk (default: `cf/upload-chunk-size`, 25 MiB).
    `:on-progress` – a fn called after every uploaded chunk with
      `{:current <uploaded-chunks> :total <total-chunks>}`, so the
      caller can show the upload while it runs."
  [blob & {:keys [chunk-size on-progress] :or {chunk-size cf/upload-chunk-size}}]
  (let [total-size   (.-size blob)
        total-chunks (js/Math.ceil (/ total-size chunk-size))]
    (->> (rp/cmd! :create-upload-session
                  {:total-chunks total-chunks})
         (rx/mapcat
          (fn [{raw-session-id :session-id}]
            (let [session-id    (cond-> raw-session-id
                                  (string? raw-session-id) uuid/uuid)
                  uploaded      (atom 0)
                  chunk-uploads
                  (->> (range total-chunks)
                       (map (fn [idx]
                              (let [start (* idx chunk-size)
                                    end   (min (+ start chunk-size) total-size)
                                    chunk (.slice blob start end)]
                                (->> (rp/cmd! :upload-chunk
                                              {:session-id session-id
                                               :index      idx
                                               :content    (list chunk (dm/str "chunk-" idx))})
                                     (rx/tap (fn [_]
                                               (when (fn? on-progress)
                                                 (on-progress {:current (swap! uploaded inc)
                                                               :total total-chunks})))))))))]
              (->> (rx/from chunk-uploads)
                   (rx/concat-all)
                   (rx/last)
                   (rx/map (fn [_] {:session-id session-id})))))))))
