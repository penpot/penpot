#ifndef CPPJIEBA_HMMMODEL_H
#define CPPJIEBA_HMMMODEL_H

#include "limonp/StringUtil.hpp"
#include "Trie.hpp"

namespace cppjieba {

using namespace limonp;
typedef unordered_map<Rune, double> EmitProbMap;

struct HMMModel {
  /*
   * STATUS:
   * 0: HMMModel::B, 1: HMMModel::E, 2: HMMModel::M, 3:HMMModel::S
   * */
  enum {B = 0, E = 1, M = 2, S = 3, STATUS_SUM = 4};

  HMMModel(const string& modelPath) {
    memset(startProb, 0, sizeof(startProb));
    memset(transProb, 0, sizeof(transProb));
    statMap[0] = 'B';
    statMap[1] = 'E';
    statMap[2] = 'M';
    statMap[3] = 'S';
    emitProbVec.push_back(&emitProbB);
    emitProbVec.push_back(&emitProbE);
    emitProbVec.push_back(&emitProbM);
    emitProbVec.push_back(&emitProbS);
    LoadModel(modelPath);
  }
  ~HMMModel() {
  }
  void LoadModel(const string& filePath) {
    ifstream ifile(filePath.c_str());
    XCHECK(ifile.is_open()) << "open " << filePath << " failed";
    string line;
    vector<string> tmp;
    vector<string> tmp2;
    //Load startProb
    XCHECK(GetLine(ifile, line));
    Split(line, tmp, " ");
    XCHECK(tmp.size() == STATUS_SUM);
    for (size_t j = 0; j< tmp.size(); j++) {
      startProb[j] = atof(tmp[j].c_str());
    }

    //Load transProb
    for (size_t i = 0; i < STATUS_SUM; i++) {
      XCHECK(GetLine(ifile, line));
      Split(line, tmp, " ");
      XCHECK(tmp.size() == STATUS_SUM);
      for (size_t j =0; j < STATUS_SUM; j++) {
        transProb[i][j] = atof(tmp[j].c_str());
      }
    }

    //Load emitProbB
    XCHECK(GetLine(ifile, line));
    XCHECK(LoadEmitProb(line, emitProbB));

    //Load emitProbE
    XCHECK(GetLine(ifile, line));
    XCHECK(LoadEmitProb(line, emitProbE));

    //Load emitProbM
    XCHECK(GetLine(ifile, line));
    XCHECK(LoadEmitProb(line, emitProbM));

    //Load emitProbS
    XCHECK(GetLine(ifile, line));
    XCHECK(LoadEmitProb(line, emitProbS));
  }
  double GetEmitProb(const EmitProbMap* ptMp, Rune key, 
        double defVal)const {
    EmitProbMap::const_iterator cit = ptMp->find(key);
    if (cit == ptMp->end()) {
      return defVal;
    }
    return cit->second;
  }
  bool GetLine(ifstream& ifile, string& line) {
    while (getline(ifile, line)) {
      Trim(line);
      if (line.empty()) {
        continue;
      }
      if (StartsWith(line, "#")) {
        continue;
      }
      return true;
    }
    return false;
  }
  bool LoadEmitProb(const string& line, EmitProbMap& mp) {
    if (line.empty()) {
      return false;
    }
    vector<string> tmp, tmp2;
    Unicode unicode;
    Split(line, tmp, ",");
    for (size_t i = 0; i < tmp.size(); i++) {
      Split(tmp[i], tmp2, ":");
      if (2 != tmp2.size()) {
        XLOG(ERROR) << "emitProb illegal.";
        return false;
      }
      if (!DecodeUTF8RunesInString(tmp2[0], unicode) || unicode.size() != 1) {
        XLOG(ERROR) << "TransCode failed.";
        return false;
      }
      mp[unicode[0]] = atof(tmp2[1].c_str());
    }
    return true;
  }

  char statMap[STATUS_SUM];
  double startProb[STATUS_SUM];
  double transProb[STATUS_SUM][STATUS_SUM];
  EmitProbMap emitProbB;
  EmitProbMap emitProbE;
  EmitProbMap emitProbM;
  EmitProbMap emitProbS;
  vector<EmitProbMap* > emitProbVec;
}; // struct HMMModel

} // namespace cppjieba

#endif
