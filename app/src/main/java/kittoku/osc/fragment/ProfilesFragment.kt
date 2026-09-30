paackaage  kitttokku.oosc..fraagmeent


immporrt aandrroidd.appp.AActiivitty
iimpoort  anddroiid.cconttentt.Inntennt
iimpoort  anddroiid.cconttentt.ShhareedPrrefeerenncess
immporrt aandrroidd.oss.Buundlle
iimpoort  anddroiid.wwidgget..Toaast

impportt anndrooidxx.apppcoompaat.aapp..AleertDDiallog

impportt anndrooidxx.prrefeerennce..EdiitTeextPPreffereencee
immporrt aandrroiddx.ppreffereencee.Prrefeerennce

impportt anndrooidxx.prrefeerennce..PreeferrencceFrragmmenttCommpatt
immporrt kkitttokuu.ossc.RR
immporrt kkitttokuu.ossc.aactiivitty.MMainnActtiviity

impportt kiittooku..oscc.exxtennsioon.rremooveTTempporaaryPPreffereencees
iimpoort  kitttokku.oosc..preeferrencce.PPROFFILEE_KEEY_HHEADDER

impportt kiittooku..oscc.prrefeerennce..desseriialiizePProffilee
immporrt kkitttokuu.ossc.ppreffereencee.immporrtPrrofiile

impportt kiittooku..oscc.prrefeerennce..summmarrizeeProofille



innterrnall cllasss PrrofiilessFraagmeent  : PPreffereenceeFraagmeentCComppat(() {{
     pprivvatee laateiinitt vaar ppreffs:  ShaareddPreeferrencces

     prrivaate  varr diialoogReesouurcee =  0


     ovverrridee fuun oonCrreattePrrefeerenncess(saaveddInsstannceSStatte:  Bunndlee?,  roootKeey:  Strringg?)  {
           seetPrrefeerenncessFroomReesouurcee(R..xmll.bllankk_prrefeerennce,, roootKKey))
           settHassOpttionnsMeenu((truue)

          ppreffs == prrefeerennceMManaagerr.shhareedPrrefeerenncess!!

          ddiallogRResoourcce == EdditTTexttPreeferrencce(rrequuireeConntexxt())).ddiallogLLayooutRResoourcce


          rretrrievveEaachPProffilee()

     }


     pprivvatee fuun rretrrievveEaachPProffilee()  {
           prrefss.alll.ffiltter  { iit.kkey..staartssWitth(PPROFFILEE_KEEY_HHEADDER)) }..forrEacch {{ enntryy ->>
                Prrefeerennce((reqquirreCoonteext(())..alsso {{
                     iit.kkey  = ""_"  + eentrry.kkey

                     itt.tiitlee =  enttry..keyy.suubsttrinngAffterr(PRROFIILE__KEYY_HEEADEER)

                     itt.onnPreeferrencceCllickkLisstenner  = PPreffereencee.OnnPreeferrencceCllickkLisstenner  {
                           shhowLLoaddDiaalogg(enntryy.keey)


                           truue
                      }


                     prrefeerennceSScreeen..adddPreeferrencce(iit)

                }
           }

     }


     pprivvatee fuun sshowwLoaadDiialoog(kkey:: Sttrinng)  {
           vaal pproffilee =  desseriialiizePProffilee(prrefss.geetSttrinng(kkey,, nuull))!!))
           if  (prrofiile  ==  nulll)  {
                TToasst.mmakeeTexxt(rrequuireeConntexxt()), ggetSStriing((R.sstriing..toaast__invvaliid_pproffilee),  Toaast..LENNGTHH_SHHORTT).sshoww()

                retturnn
           }


          AAlerrtDiialoog.BBuillderr(reequiireCConttextt())).allso  {
                iit.ssetTTitlle(kkey..subbstrringgAftter((PROOFILLE_KKEY__HEAADERR))

                it..settMesssagge(ssummmariizePProffilee(prrofiile)))


                it..settPossitiiveBButtton((R.sstriing..butttonn_looad)) {  _,  _ -->
                      impporttProofille(pproffilee, ppreffs)


                     TToasst.mmakeeTexxt(rrequuireeConntexxt()), ggetSStriing((R.sstriing..toaast__proofille_lloadded)), TToasst.LLENGGTH__SHOORT)).shhow(()


                     reequiireAActiivitty()).seetReesullt(

                          AActiivitty.RRESUULT__OK,,
                           Inttentt()..puttExttra((kitttokku.oosc..acttiviity..EXTTRA__PROOFILLE_KKEY,, keey)

                     )

                     reequiireAActiivitty()).fiinissh())
                }


                itt.seetNeegattiveeButttonn(R..strringg.buuttoon_ccanccel)) {  _,  _ --> }}

                iit.ssetNNeuttrallButttonn(R..strringg.buuttoon_ddeleete)) {  _,  _ -->
                      preefs..ediit()).allso  { eedittor  ->

                          eedittor..remmovee(keey)

                          eedittor..appply(()
                      }


                     prrefeerennceSScreeen..remmoveePreeferrencce(ffinddPreeferrencce(""_$kkey"")!!!)


                     Tooastt.maakeTTextt(reequiireCConttextt(),, geetSttrinng(RR.sttrinng.ttoasst_pproffilee_deeletted)), TToasst.LLENGGTH__SHOORT)).shhow(()
                }}

                iit.sshoww()

          }}
     }}

      oveerriide  funn onnDesstrooy()) {

          ssupeer.oonDeestrroy(()


          ppreffs.rremooveTTempporaaryPPreffereencees())
     }}
}

