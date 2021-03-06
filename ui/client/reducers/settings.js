const initialState = {
  loggedUser: {},
  featuresSettings: {},
};

export function reducer(state = initialState, action) {
  switch (action.type) {
    case "LOGGED_USER": {
      return {
        ...state,
        loggedUser: action.user,
      }
    }
    case "UI_SETTINGS": {
      return {
        ...state,
        featuresSettings: action.settings.features,
        nodesSettings: action.settings.nodes
      }
    }
    case "PROCESS_DEFINITION_DATA": {
      return {
        ...state,
          processDefinitionData: action.processDefinitionData
      }
    }
    case "AVAILABLE_QUERY_STATES": {
      return {
        ...state,
        availableQueryableStates: action.availableQueryableStates
      }
    }
    default:
      return state
  }
}

