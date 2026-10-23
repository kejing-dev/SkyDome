IV/27A            HD-DM-GC-HR-HIP-Bayer-Flamsteed Cross Index    (Kostjuk, 2002)
================================================================================
HD-DM-GC-HR-HIP-Bayer-Flamsteed Cross Index
     Kostjuk N.D.
    <Institute of Astronomy of Russian Academy of Sciences (2002)>
================================================================================
ADC_Keywords: Cross identifications

Abstract:
    New Cross Index is constructed on the basis of data analyse of basic
    catalogs: BSC5 (<V/50>), Hipparcos (<I/196>) and CSI (<IV/9>). The
    full list of the stars with HD-number and Bayer-Flamsteed designations
    with later extension of various authors was edited according to
    printed atlases [1,2,3,4]. Some names of variable stars from these
    atlases were added to the list.

    The resulting list was edited after the review and the analyses of
    star names in catalogue list 'refs.dat'. The DM, GC, HR and HIP
    numbers were added. The DM numbers were taken from the Henry Draper
    Catalogue (<III/135>),the GC numbers were taken from General Catalogue
    of 33342 stars (<I/113>),the HR numbers were taken from BSC5 (<V/50>).
    Finally the list of proper names of stars was made from information
    analyses of the atlases [1,2,3,4] and the catalogs BSC5, Hipparcos,
    CSI and <IV/22>. The right ascensions, declinations and visual
    magnitudes for all stars were taken from Hipparcos and CSI catalogue
    if the star has no number in Hipparcos.

File Summary:
--------------------------------------------------------------------------------
 FileName   Lrecl  Records   Explanations
--------------------------------------------------------------------------------
ReadMe         80        .   This file
catalog.dat    77     3690   HD-DM-GC-HR-HIP-Bayer-Flamsteed Cross Index
table1.dat     64     1226  *List of alternative names for 454 stars
                             from the file catalog.dat
table2.dat     99       58   List of alternative HD number or DM number
                              for 48 stars from the file catalog.dat
table3.dat    100      807   List of proper names for 345 stars from the
                              file catalog.dat
refs.dat      143       26   List of source catalogs and atlases
addendum.dat   77      277   Addendum (see the "History" section below)
--------------------------------------------------------------------------------
Note on table1.dat: This table list historical names, some of then have been 
  changed for official names. File catalog.dat list official names.
--------------------------------------------------------------------------------

See also:
    V/50    : Bright Star Catalogue, 5th Revised Ed. (Hoffleit+, 1991)
    I/196   : Hipparcos Input Catalogue, Version 2 (Turon+ 1993)
    I/239   : The Hipparcos and Tycho Catalogues (ESA 1997)
    IV/9    : Catalogue of Stellar Identifications, edition 1979
    III/135 : Henry Draper Catalogue and Extension (Cannon+ 1918-1924; ADC 1989)
    I/113   : General Catalogue of 33342 stars (GC) (Boss 1937)
    IV/22   : FK5-SAO-HD-Common Name Cross Index (Smith, 1996)

Byte-by-byte Description of file: catalog.dat
--------------------------------------------------------------------------------
   Bytes Format Units   Label   Explanations
--------------------------------------------------------------------------------
   1-  6  I6    ---     HD      [1/257937] Henry Draper Catalog Number <III/135>
   8- 19  A12   ---     DM      Durchmusterung Identification from HD Catalog
                                  <III/135> (1)
  21- 25  I5    ---     GC      [1/33342]? Boss General Catalog (GC, <I/113>)
                                   number if one exists, otherwise blank
  27- 30  I4    ---     HR      [1/9110]? Harvard Revised Number=Bright Star
                                   Number <V/50> if one exists, otherwise blank
  32- 37  I6    ---     HIP     [1/120416]? Hipparcos Catalog <I/196> number
                                   if one exists, otherwise blank
  39- 40  I2    h       RAh     Right Ascension J2000 (hours) (2)
  41- 42  I2    min     RAm     Right Ascension J2000 (minutes) (2)
  43- 47  F5.2  s       RAs     Right Ascension J2000 (seconds) (2)
      49  A1    ---     DE-     Declination J2000 (sign)
  50- 51  I2    deg     DEd     Declination J2000 (degrees) (2)
  52- 53  I2    arcmin  DEm     Declination J2000 (minutes) (2)
  54- 57  F4.1  arcsec  DEs     Declination J2000 (seconds) (2)
  59- 63  F5.2  mag     Vmag    [-1.44/13.4]? Visual magnitude (2)
  65- 67  I3    ---     Fl      ? Flamsteed number (G1)
  69- 73  A5    ---     Bayer   Bayer designation (G1)
  75- 77  A3    ---     Cst     Constellation abbreviation (G1)
--------------------------------------------------------------------------------
Note (1): Durchmusterung Identification from HD Catalog <III/135>
    consists of DM Catalog designation:
      BD north of -23 degrees,
      CD from -23 to -52 degrees,
      CP south of -52 degrees
    Sign of DM zone, DM zone, DM number and component identification if
    there are two or more HD stars with the same DM number (for multiple
    systems included in the Washington Catalog of Visual Double Stars the
    same designation are given).

Note (2): Right ascensions, declinations and visual magnitudes for all
    stars were taken from the Hipparcos catalog and from the CSI for the
    stars that has no number in catalog Hipparcos.
--------------------------------------------------------------------------------

Byte-by-byte Description of file: table1.dat
--------------------------------------------------------------------------------
   Bytes Format Units   Label   Explanations
--------------------------------------------------------------------------------
   1-  6  I6    ---     HD      [1/257937] Henry Draper Catalog number
   8- 20  A13   ---     BFD     Bayer-Flamsteed designation of star
      22  A1    ---   n_BFD     [+ ] +: Bayer-Flamsteed designation accepted
                                     in 'catalog.dat', otherwise - in the
                                     catalogues of 'refs.dat'
  24- 64  A41   ---   r_BFD     References detailed in 'refs.dat', where this
                                 BFD-designation of star is taken
--------------------------------------------------------------------------------

Byte-by-byte Description of file: table2.dat
--------------------------------------------------------------------------------
   Bytes Format Units   Label   Explanations
--------------------------------------------------------------------------------
   1- 13  A13   ---     BFD1    Bayer-Flamsteed designation of star accepted in
                                 the catalog (1)
  15- 27  A13   ---     BFD2    Bayer-Flamsteed designation of star in other
                                 catalogs  (1)
  30- 40  A11   ---     ID1     HD or DM number in the catalog for the star BFD1
  43- 71  A29   ---   r_BFD1    References detailed in 'refs.dat' for BFD1
  73- 83  A11   ---     ID2     Another HD or DM number for the BFD2
  86- 99  A14   ---   r_BFD2    References detailed in 'refs.dat' for BFD2
--------------------------------------------------------------------------------
Note (1): In some cases (such as 55 Cas,52 Per) the star has various
    HD numbers but it is not pointed that this star is double.
--------------------------------------------------------------------------------

Byte-by-byte Description of file: table3.dat
--------------------------------------------------------------------------------
   Bytes Format Units   Label   Explanations
--------------------------------------------------------------------------------
   1-  6  I6    ---     HD      [1/257937] Henry Draper Catalog number
   8- 20  A13   ---     BFD     Bayer-Flamsteed designation of star in catalog
  22- 76  A55   ---     Name    Proper name for the star
  78-100  A23   ---   r_Name    References detailed in 'refs.dat'
--------------------------------------------------------------------------------

Byte-by-byte Description of file: refs.dat
--------------------------------------------------------------------------------
   Bytes Format Units   Label   Explanations
--------------------------------------------------------------------------------
   1-  2  I2    ---     Ref     [1/26] Reference number
   4- 10  A7    ---     Cat     Number of the catalogue in the CDS
  12-143  A132  ---     Text    Authors, name and complete reference
--------------------------------------------------------------------------------

Byte-by-byte Description of file: addendum.dat
--------------------------------------------------------------------------------
   Bytes Format Units   Label   Explanations
--------------------------------------------------------------------------------
   1-  6  I6    ---     HD      ? Henry Draper Catalog Number <III/135>
       7  A1    ---   n_HD      [x*N] Duplicate, constellation, or Nova (1)
  27- 30  I4    ---     HR      [1/9110]?  Harvard Revised Number=Bright Star
                                   Number <V/50> if one exists, otherwise blank
  32- 37  I6    ---     HIP     [1/120416]? Hipparcos Catalog <I/196> number
                                   if one exists, otherwise blank
  39- 40  I2    h       RAh     Right Ascension J2000 (hours)
  41- 42  I2    min     RAm     Right Ascension J2000 (minutes)
  43- 47  F5.2  s       RAs     Right Ascension J2000 (seconds)
      49  A1    ---     DE-     Declination J2000 (sign)
  50- 51  I2    deg     DEd     Declination J2000 (degrees)
  52- 53  I2    arcmin  DEm     Declination J2000 (minutes)
  54- 57  F4.1  arcsec  DEs     Declination J2000 (seconds)
  59- 63  F5.2  mag     Vmag    ? Visual magnitude
  65- 67  I3    ---     Fl      ? Flamsteed number (G1)
  69- 73  A5    ---     Bayer   Bayer designation (G1)
  75- 77  A3    ---     Cst     Constellation abbreviation (G1)
--------------------------------------------------------------------------------
Note (1): flag as follows:
    x = duplicated entry (2 different names for this star)
    * = name refers to a constellation outside the official one
    N = nova (concerns 11 Vul = CK Vul = Nova Vul 1670, not in HD)
--------------------------------------------------------------------------------

Global Notes:
Note (G1): Bayer-Flamsteed designation of star with its extension consists
    in a Flamsteed number if one exists, otherwise blank, a Bayer
    designation of star with greek letter or latin letter according to
    various authors, superscript number for letter designation if one
    exists, otherwise blank, constellation abbreviation.

    Abbreviations for the greek letters (the same as in catalog Hipparcos):
    alpha = alf   beta= bet   gamma= gam   delta= del   epsilon= eps
    dzeta = zet   eta = eta   theta= the   iota = iot   kappa  = kap
    lambda= lam   mu  = mu.   nu   = nu.   xi   = ksi   omicron= omi
    pi    = pi.   rho = rho   sigma= sig   tau  = tau   upsilon= ups
    phi   = phi   chi = chi   psi  = psi   omega= ome
--------------------------------------------------------------------------------

References:
  [1] Mikhajlov, Atlas zvezdnogo neba, "Nauka", Leningrad, 1974
  [2] Rak & Kholshevnikov, Atlas zvezdnogo neba, Sankt-Peterburg, "Neva", 1995
  [3] Becvar, Atlas CoeliII- Katalog 1950.0, Praha, 1964
  [4] Kulikovskij, Spravochnik lyubitelya astronomii, Moskva, 1961, 1971, 2002

History:
  * 16-Apr-2004: Prepared by Veta Avedisova [INASAN]
  * 28-Dec-2010: a few incomplete constellation names fixed (CDS);
  * 01-Jan-2011: added the file "addendum.dat" prepared by Bill J. Gray
    (projectpluto.com), adapted from Wagman (1987JHA....18..209W)
  * 30-Jun-2020: catalog version in VizieR as catalog.dat

================================================================================
(End)                      Veta Avedisova [INASAN]                   16-Apr-2004
