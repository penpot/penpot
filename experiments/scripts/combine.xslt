<?xml version="1.0" ?> 
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0" xmlns:lxslt="http://xml.apache.org/xslt" 
 xmlns:svg="http://www.w3.org/2000/svg"> 
<xsl:param name="prepend">default</xsl:param>
<xsl:variable name="testvar"><xsl:value-of select="$prepend"/></xsl:variable>

<xsl:output method="xml" indent="yes"/> 

<xsl:template match="*|@*|text()">
<xsl:copy>
<xsl:apply-templates select="*|@*|text()"/>
</xsl:copy>
</xsl:template>

<xsl:template match="svg:svg">
<xsl:copy>
<xsl:apply-templates select="@*"/>
<!--<xsl:value-of select="$testvar"/>-->
<xsl:apply-templates select="document($prepend)/svg:svg/svg:g"/>
<xsl:apply-templates select="svg:g"/>
</xsl:copy>
</xsl:template>


</xsl:stylesheet>

 
