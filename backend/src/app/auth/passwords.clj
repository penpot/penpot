;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.auth.passwords
  "Password strength validation using Passay library."
  (:require
   [app.common.exceptions :as ex])
  (:import
   [org.passay CharacterCharacteristicsRule CharacterRule EnglishCharacterData PasswordData]))

(defonce ^:private passay-code->translation-key
  {"INSUFFICIENT_LOWERCASE"   "errors.weak-password.insufficient-lowercase"
   "INSUFFICIENT_UPPERCASE"   "errors.weak-password.insufficient-uppercase"
   "INSUFFICIENT_DIGIT"       "errors.weak-password.insufficient-digits"
   "INSUFFICIENT_SPECIAL"     "errors.weak-password.insufficient-special"})

(defonce ^:private character-characteristics-rule
  (doto (CharacterCharacteristicsRule.)
    (.setRules [(CharacterRule. EnglishCharacterData/LowerCase 1)
                (CharacterRule. EnglishCharacterData/UpperCase 1)
                (CharacterRule. EnglishCharacterData/Digit 1)
                (CharacterRule. EnglishCharacterData/Special 1)])
    (.setNumberOfCharacteristics 4)))

(defn validate-password
  "Validates password strength.
   Returns nil if valid, or raises exception if invalid.
   Checks:
   - Minimum length of 8 characters
   - At least 1 lowercase letter
   - At least 1 uppercase letter
   - At least 1 digit
   - At least 1 special character"
  [password]
  (when (< (count password) 8)
    (ex/raise :type :validation
              :code :weak-password
              :hint "password must be at least 8 characters"
              :details ["errors.weak-password.too-short"]))

  (let [password-data (PasswordData. password)
        char-result   (.validate character-characteristics-rule password-data)]
    (when-not (.isValid char-result)
      (ex/raise :type :validation
                :code :weak-password
                :hint "password must contain at least 1 lowercase letter, 1 uppercase letter, 1 digit, and 1 special character"
                :details (->> (.getDetails char-result)
                              (mapv #(.getErrorCode %))
                              (mapv passay-code->translation-key)
                              (filterv some?))))))
