/*
 * Copyright (c) 2002-2018, Mairie de Paris
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  1. Redistributions of source code must retain the above copyright notice
 *     and the following disclaimer.
 *
 *  2. Redistributions in binary form must reproduce the above copyright notice
 *     and the following disclaimer in the documentation and/or other materials
 *     provided with the distribution.
 *
 *  3. Neither the name of 'Mairie de Paris' nor 'Lutece' nor the names of its
 *     contributors may be used to endorse or promote products derived from
 *     this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 * License 1.0
 */
package fr.paris.lutece.plugins.forms.service;

import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;

import org.springframework.transaction.annotation.Transactional;

import fr.paris.lutece.plugins.forms.business.Form;
import fr.paris.lutece.plugins.forms.business.FormDisplay;
import fr.paris.lutece.plugins.forms.business.FormHome;
import fr.paris.lutece.plugins.forms.business.FormMessageHome;
import fr.paris.lutece.plugins.forms.business.FormQuestionResponse;
import fr.paris.lutece.plugins.forms.business.FormQuestionResponseHome;
import fr.paris.lutece.plugins.forms.business.FormResponse;
import fr.paris.lutece.plugins.forms.business.FormResponseHome;
import fr.paris.lutece.plugins.forms.business.FormResponseStep;
import fr.paris.lutece.plugins.forms.business.FormResponseStepHome;
import fr.paris.lutece.plugins.forms.business.Question;
import fr.paris.lutece.plugins.forms.business.Step;
import fr.paris.lutece.plugins.forms.business.StepHome;
import fr.paris.lutece.plugins.forms.business.form.search.IndexerAction;
import fr.paris.lutece.plugins.forms.service.search.IFormSearchIndexer;
import fr.paris.lutece.plugins.forms.service.workflow.IFormWorkflowService;
import fr.paris.lutece.plugins.forms.util.FormsConstants;
import fr.paris.lutece.plugins.forms.web.CompositeGroupDisplay;
import fr.paris.lutece.plugins.forms.web.CompositeQuestionDisplay;
import fr.paris.lutece.plugins.forms.web.FormResponseManager;
import fr.paris.lutece.plugins.forms.web.ICompositeDisplay;
import fr.paris.lutece.plugins.forms.web.StepDisplayTree;
import fr.paris.lutece.plugins.forms.web.admin.MultiviewFormResponseDetailsJspBean;
import fr.paris.lutece.plugins.forms.web.entrytype.IEntryDataService;
import fr.paris.lutece.plugins.genericattributes.business.Entry;
import fr.paris.lutece.plugins.genericattributes.business.EntryHome;
import fr.paris.lutece.plugins.genericattributes.business.Response;
import fr.paris.lutece.plugins.genericattributes.business.ResponseHome;
import fr.paris.lutece.plugins.genericattributes.service.entrytype.AbstractEntryTypeFile;
import fr.paris.lutece.plugins.genericattributes.service.entrytype.EntryTypeServiceManager;
import fr.paris.lutece.plugins.genericattributes.service.entrytype.IEntryTypeService;
import fr.paris.lutece.portal.business.user.AdminUser;
import fr.paris.lutece.portal.service.admin.AdminUserService;
import fr.paris.lutece.portal.service.rbac.RBACService;
import fr.paris.lutece.portal.service.spring.SpringContextService;
import fr.paris.lutece.portal.service.util.AppLogService;
import fr.paris.lutece.portal.service.workgroup.AdminWorkgroupService;

/**
 * This is the service class related to the form
 */
public class FormService
{
    public static final String BEAN_NAME = "forms.formService";

    @Inject
    private IFormWorkflowService _formWorkflowService;
    @Inject
    private IFormSearchIndexer _formSearchIndexer;

    /**
     * Saves the specified form
     * 
     * @param form
     *            the form to save
     * @param formResponse
     *            the form response to save
     */
    @Transactional( FormsConstants.BEAN_TRANSACTION_MANAGER )
    public void saveForm( Form form, FormResponse formResponse )
    {
        formResponse.setFromSave( Boolean.FALSE );

        filterFinalSteps( formResponse );
        saveFormResponse( formResponse );
        saveFormResponseSteps( formResponse );
        processIncrementalIndexing( formResponse ); 
        _formWorkflowService.doProcessActionOnFormCreation( form, formResponse );
    }

    /**
     * Filters the responses to keep the final responses
     * 
     * @param formResponse
     *            the form response containing the responses to filter
     */
    private void filterFinalSteps( FormResponse formResponse )
    {
        formResponse.setSteps( formResponse.getSteps( ).stream( ).filter( step -> step.getOrder( ) != FormsConstants.ORDER_NOT_SET )
                .collect( Collectors.toList( ) ) );
    }

    /**
     * Saves the form response
     * 
     * @param formResponse
     *            the form response to save
     */
    private void saveFormResponse( FormResponse formResponse )
    {
        if ( formResponse.getId( ) > 0 )
        {
            FormResponseHome.update( formResponse );

            for ( FormQuestionResponse formQuestionResponse : FormQuestionResponseHome.getFormQuestionResponseListByFormResponse( formResponse.getId( ) ) )
            {
                FormQuestionResponseHome.remove( formQuestionResponse );
            }

            FormResponseStepHome.removeByFormResponse( formResponse.getId( ) );
        }
        else
        {
            FormResponseHome.create( formResponse );
        }
    }

    /**
     * Saves the form response steps
     * 
     * @param formResponse
     *            the form response containing the form response steps to save
     */
    private void saveFormResponseSteps( FormResponse formResponse )
    {
        for ( FormResponseStep formResponseStep : formResponse.getSteps( ) )
        {
            formResponseStep.setFormResponseId( formResponse.getId( ) );

            saveFormQuestionResponse( formResponseStep );

            FormResponseStepHome.create( formResponseStep );
        }
    }

    /**
     * Saves the form question responses of the specified step
     * 
     * @param formResponseStep
     *            the form response step containing the form questions responses to save
     */
    private void saveFormQuestionResponse( FormResponseStep formResponseStep )
    {
        for ( FormQuestionResponse formQuestionResponse : formResponseStep.getQuestions( ) )
        {
            Question question = formQuestionResponse.getQuestion( );

            if ( question != null && question.isVisible( ) )
            {
                IEntryDataService dataService = EntryServiceManager.getInstance( ).getEntryDataService( question.getEntry( ).getEntryType( ) );
                formQuestionResponse.setIdFormResponse( formResponseStep.getFormResponseId( ) );
                dataService.save( formQuestionResponse );
            }
        }
    }

    /**
     * Saves the specified form for a backup
     * 
     * @param formResponse
     *            The form response to save
     */
    @Transactional( FormsConstants.BEAN_TRANSACTION_MANAGER )
    public void saveFormForBackup( FormResponse formResponse )
    {
        formResponse.setFromSave( Boolean.TRUE );

        saveFormResponse( formResponse );
        saveFormResponseSteps( formResponse );
    }

    /**
     * Get the full children composite list of the given step
     * 
     * @param nIdStep
     *            The step primary key
     * @return the Html of the given step
     */
    public List<ICompositeDisplay> getStepCompositeList( int nIdStep )
    {
        StepDisplayTree displayTree = new StepDisplayTree( nIdStep );

        return displayTree.getCompositeList( );
    }

    /**
     * Get the right composite from the given formDisplay
     * 
     * @param formDisplay
     *            The formDisplay
     * @param formResponse
     *            the form response
     * @param nIterationNumber
     *            the iteration number
     * @return the right composite
     */
    public ICompositeDisplay formDisplayToComposite( FormDisplay formDisplay, FormResponse formResponse, int nIterationNumber )
    {
        ICompositeDisplay composite = null;
        if ( FormsConstants.COMPOSITE_GROUP_TYPE.equals( formDisplay.getCompositeType( ) ) )
        {
            composite = new CompositeGroupDisplay( formDisplay, formResponse, nIterationNumber );

        }
        else
            if ( FormsConstants.COMPOSITE_QUESTION_TYPE.equals( formDisplay.getCompositeType( ) ) )
            {
                composite = new CompositeQuestionDisplay( formDisplay, formResponse, nIterationNumber );
            }

        return composite;
    }

    /**
     * Remove a given Form, all its steps and composites, workflow resources. Also remove all the related formResponses, QuestionsResposes, EntryResponses and
     * entries.
     * 
     * @param nIdForm
     *            The identifier of the form to be deleted
     * @param adminUser
     *            the user
     */
    @Transactional( FormsConstants.BEAN_TRANSACTION_MANAGER )
    public void removeForm( int nIdForm, AdminUser adminUser )
    {
        StepService stepService = SpringContextService.getBean( StepService.BEAN_NAME );

        List<Step> listStep = StepHome.getStepsListByForm( nIdForm );

        for ( Step step : listStep )
        {
            stepService.removeStep( step.getId( ) );
        }

        FormResponseHome.removeByForm( nIdForm );
        FormMessageHome.removeByForm( nIdForm );

        Form form = FormHome.findByPrimaryKey( nIdForm );
        int nIdWorkflow = form.getIdWorkflow( );

        FormHome.remove( nIdForm );

        _formWorkflowService.removeResources( nIdWorkflow, nIdForm, adminUser );
    }

    /**
     * Check if a user is authorized to access a File from its given identifier
     * 
     * @param request
     *            The request to use to retrieve information of the current user
     * @param nIdResponse
     *            The identifier of the Response which have the file
     * @param nIdFile
     *            The identifier of the file to access
     * @return the boolean which tell if the user is authorize to access the given File or not
     */
    public boolean isFileAccessAuthorized( HttpServletRequest request, int nIdResponse, int nIdFile )
    {
        boolean bFileAccessAuthorized = Boolean.FALSE;

        Response response = ResponseHome.findByPrimaryKey( nIdResponse );
        if ( response != null && response.getEntry( ) != null && response.getFile( ) != null && response.getFile( ).getIdFile( ) == nIdFile )
        {
            Entry entryResponse = EntryHome.findByPrimaryKey( response.getEntry( ).getIdEntry( ) );
            IEntryTypeService entryTypeService = EntryTypeServiceManager.getEntryTypeService( entryResponse );

            if ( entryTypeService instanceof AbstractEntryTypeFile && Form.RESOURCE_TYPE.equals( entryResponse.getResourceType( ) ) )
            {
                bFileAccessAuthorized = canUserAccessFile( request, entryResponse.getIdResource( ) );
            }
        }

        return bFileAccessAuthorized;
    }

    /**
     * Check if a user have all necessaries permissions to access to file in the formResponse details view
     * 
     * @param request
     *            The request to use to retrieve the user
     * @param nIdForm
     *            The identifier of the Form to use to check the permissions
     * @return true if the user can access File false if the user doesn't have necessary permissions
     */
    private boolean canUserAccessFile( HttpServletRequest request, int nIdForm )
    {
        boolean bUserAccessFile = Boolean.FALSE;

        AdminUser adminUser = AdminUserService.getAdminUser( request );
        if ( adminUser != null && adminUser.checkRight( MultiviewFormResponseDetailsJspBean.RIGHT_FORMS_MULTIVIEW ) )
        {
            Form form = FormHome.findByPrimaryKey( nIdForm );
            if ( form != null && AdminWorkgroupService.isAuthorized( form, adminUser ) )
            {
                boolean bRbacModify = RBACService.isAuthorized( Form.RESOURCE_TYPE, Integer.toString( form.getId( ) ),
                        FormsResourceIdService.PERMISSION_MODIFY_FORM_RESPONSE, adminUser );

                boolean bRbacManage = RBACService.isAuthorized( Form.RESOURCE_TYPE, Integer.toString( form.getId( ) ),
                        FormsResourceIdService.PERMISSION_MANAGE_FORM_RESPONSE, adminUser );

                boolean bRbacView = RBACService.isAuthorized( Form.RESOURCE_TYPE, Integer.toString( form.getId( ) ),
                        FormsResourceIdService.PERMISSION_VIEW_FORM_RESPONSE, adminUser );

                bUserAccessFile = bRbacModify || bRbacManage || bRbacView;
            }
        }

        return bUserAccessFile;
    }

    /**
     * Creates a {@code FormResponseManager} object from a back up
     * 
     * @param form
     *            The form
     * @param strUserGuid
     *            The user guid
     * @return the created {@code FormResponseManager} object
     */
    public FormResponseManager createFormResponseManagerFromBackUp( Form form, String strUserGuid )
    {
        FormResponseManager formResponseManager = null;

        FormResponse formResponse = FormResponseHome.getFormResponseByGuidAndForm( strUserGuid, form.getId( ) );

        if ( formResponse != null )
        {
            formResponseManager = new FormResponseManager( formResponse );
        }
        else
        {
            formResponseManager = new FormResponseManager( form );
        }

        return formResponseManager;
    }
    
    /**
     * Process incremental indexing for create formResponse
     * @param formResponse 
     */
    private void processIncrementalIndexing( FormResponse formResponse )
    {
        //Add an action CREATE for given formResponse
        try
        {
        _formSearchIndexer.addIndexerAction( formResponse.getId( ), IndexerAction.TASK_CREATE, FormsPlugin.getPlugin( ) );
        _formSearchIndexer.processIncrementalIndexing( null );
        }
        catch( Exception e )
        {
            AppLogService.error( "Unable to index form response with id = " + formResponse.getId( ), e);
        }
    }
}
