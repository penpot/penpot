;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.util.totp
  (:import
   dev.samstevens.totp.code.DefaultCodeGenerator
   dev.samstevens.totp.code.DefaultCodeVerifier
   dev.samstevens.totp.code.CodeVerifier
   dev.samstevens.totp.code.HashingAlgorithm
   dev.samstevens.totp.qr.QrData
   dev.samstevens.totp.qr.QrData$Builder
   dev.samstevens.totp.qr.ZxingPngQrGenerator
   dev.samstevens.totp.secret.DefaultSecretGenerator
   dev.samstevens.totp.time.SystemTimeProvider
   dev.samstevens.totp.util.Utils))

(defn get-verifier
  []
  (DefaultCodeVerifier.
   (DefaultCodeGenerator. HashingAlgorithm/SHA1  6)
   (SystemTimeProvider.)))

(defn valid-code?
  [secret code]
  (let [verifier (doto (get-verifier)
                   (.setTimePeriod 30)
                   (.setAllowedTimePeriodDiscrepancy 2))
        result   (.isValidCode ^CodeVerifier verifier
                               ^String secret
                               ^String code)]
    result))

(defn gen-secret
  ([] (gen-secret 32))
  ([n]
   (let [sgen (DefaultSecretGenerator. (int n))]
     (.generate ^DefaultSecretGenerator sgen))))


(defn get-qrcode-image
  [secret email]
  (let [data  (.. (QrData$Builder.)
                  (label ^String email)
                  (secret ^String secret)
                  (issuer "Penpot")
                  (digits 6)
                  (period 30)
                  (build))
        imgen  (ZxingPngQrGenerator.)
        imgdt  (.generate imgen ^QrData data)
        imgmt  (.getImageMimeType imgen)]
    (Utils/getDataUriForImage imgdt imgmt)))

