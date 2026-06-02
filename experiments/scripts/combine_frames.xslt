<?xml version="1.0" ?> 
<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0" xmlns:lxslt="http://xml.apache.org/xslt" 
 xmlns:svg="http://www.w3.org/2000/svg"> 
<xsl:param name="append_file">default</xsl:param>
<xsl:param name="append_id">-1</xsl:param>

<xsl:output method="xml" indent="yes"/> 

<xsl:template match="*|@*|text()">
<xsl:copy>
<xsl:apply-templates select="*|@*|text()"/>
</xsl:copy>
</xsl:template>

<xsl:template match="svg:svg">
<xsl:copy>
<xsl:apply-templates select="@*"/>
<xsl:apply-templates select="*"/>
<xsl:apply-templates select="document($append_file)/svg:svg" mode="append"/>
</xsl:copy>
</xsl:template>

<xsl:template match="svg:svg" mode="append">
<svg:g id="{$append_id}" display="none">
<xsl:apply-templates select="svg:g"/>
</svg:g>
</xsl:template>

</xsl:stylesheet>

 
